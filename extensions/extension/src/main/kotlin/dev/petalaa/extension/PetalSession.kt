package dev.petalaa.extension

import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Android Auto session that provides the map [Screen] and manages
 * the [SurfaceCallback] lifecycle.
 *
 * The [SurfaceCallback] is registered with [AppManager] once per session
 * and survives screen transitions. It creates a [VirtualDisplay] on the
 * host-provided surface and projects `PetalMapsActivity` onto it.
 *
 * ## Resize handling
 *
 * When the head unit changes display configuration (rotation, split-screen,
 * etc.), [onVisibleAreaChanged] / [onStableAreaChanged] are called.
 * If dimensions differ meaningfully (>= 5%) from the current VirtualDisplay,
 * we schedule a recreation debounced by ~700ms — the host often sends a burst
 * of area updates, and recreating per update kills the activity the touch
 * events are being dispatched to. Identical or sub-5% changes are ignored.
 */
class PetalSession : Session() {

    private val carDisplay by lazy { CarDisplay(carContext) }
    private var surfaceCallbackRegistered = false

    /** The most recent SurfaceContainer — saved for resize recreation. */
    private var lastContainer: SurfaceContainer? = null

    /** Main-thread handler used to debounce resize-driven recreations. */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Pending debounced recreation runnable, if any. */
    private var pendingRecreate: Runnable? = null

    override fun onCreateScreen(intent: Intent): Screen {
        AALogger.i("onCreateScreen: creating MapScreen")

        // Register the SurfaceCallback once per session lifetime.
        if (!surfaceCallbackRegistered) {
            surfaceCallbackRegistered = true
            AALogger.i("Registering SurfaceCallback with AppManager")
            val appManager = carContext.getCarService(AppManager::class.java)
            appManager.setSurfaceCallback(createSurfaceCallback())
            AALogger.shareableCopy()

            // Enable car mode once per session: the wakelock keeps the
            // VirtualDisplay composition alive; keyguard and physical panel
            // are managed inside enableCarMode().
            enableCarMode()
        }

        return MapScreen(carContext, carDisplay)
    }

    // ---- Car mode (wakelock + keyguard off + panel off while projecting) ---

    /** Held for the whole car session so SurfaceFlinger keeps composing. */
    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null

    /** Maintenance thread re-powering the panel off and renewing the wakelock. */
    @Volatile
    private var panelOffThread: Thread? = null

    /**
     * During the car session: hold a SCREEN_BRIGHT wakelock (keeps the display
     * logically ON so VirtualDisplay composition keeps running — no root
     * needed for that), disable the keyguard and power the physical panel
     * OFF — projection does not need the panel (the VD is virtual) and this
     * saves battery. Wake sources (notifications, charger) can re-power the
     * panel, so a maintenance loop re-enforces panel-off and renews the
     * wakelock every ~30s. Everything is restored when the session ends
     * ([disableCarMode]).
     */
    private fun enableCarMode() {
        if (wakeLock != null) return
        val pm = carContext.getSystemService(PowerManager::class.java)
        wakeLock = pm?.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
            "PetalAA:aaSession"
        )?.apply {
            setReferenceCounted(false)
            acquire(WAKELOCK_TIMEOUT_MS)
        }
        if (wakeLock == null) {
            AALogger.e("CarMode: PowerManager unavailable — car mode not enabled")
            return
        }
        AALogger.i("CarMode: SCREEN_BRIGHT_WAKE_LOCK acquired (${WAKELOCK_TIMEOUT_MS}ms timeout)")
        AALogger.w(
            "CarMode: if the app dies while enabled, the panel powers back on the next " +
                "natural wake, but lockscreen_disabled stays 1 until a next session reverts it"
        )

        panelOffThread = thread(name = "PetalAACarMode") {
            if (wakeLock == null) return@thread // session ended before we started
            runCarModeCommand("settings put secure lockscreen_disabled 1")
            while (true) {
                val wl = wakeLock ?: break
                // The wakelock has a safety timeout — renew it every cycle so
                // a long car session never lets the display suspend.
                wl.acquire(WAKELOCK_TIMEOUT_MS)
                // Raced with disableCarMode()? Undo the stray re-acquire.
                if (wakeLock !== wl) {
                    if (wl.isHeld) wl.release()
                    break
                }
                runCarModeCommand("cmd display power-off 0")
                try {
                    Thread.sleep(PANEL_REOFF_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        AALogger.shareableCopy()

        // car-app 1.7.0 has no Session.onCarSessionFinished(); the session
        // lifecycle reaches DESTROYED when the car connection ends
        // (CarAppBinder.onAppDestroy -> handleLifecycleEvent(ON_DESTROY)).
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                AALogger.i("CarMode: session lifecycle destroyed — reverting")
                disableCarMode()
            }
        })
    }

    /**
     * Stops the maintenance thread, releases the wakelock and restores the
     * panel and keyguard. Idempotent. Root commands run on a background
     * thread — session lifecycle callbacks arrive on the main thread.
     */
    private fun disableCarMode() {
        if (wakeLock == null) return
        panelOffThread?.interrupt()
        panelOffThread = null
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
        AALogger.i("CarMode: wakelock released — restoring panel + keyguard")
        thread(name = "PetalAACarModeRestore") {
            runCarModeCommand("cmd display power-on 0")
            runCarModeCommand("settings put secure lockscreen_disabled 0")
            AALogger.shareableCopy()
        }
    }

    /** Runs one car-mode shell command and logs command and result. */
    private fun runCarModeCommand(command: String) {
        AALogger.i("CarMode: executing '$command'")
        val (exit, _, stderr) = RootShell.run(command)
        if (exit == 0) {
            AALogger.i("CarMode: '$command' -> OK")
        } else {
            AALogger.e("CarMode: '$command' -> FAILED (exit=$exit, stderr=${stderr.trim()})")
        }
    }

    // ---- SurfaceCallback factory ------------------------------------------

    private fun createSurfaceCallback(): SurfaceCallback {
        return object : SurfaceCallback {

            override fun onSurfaceAvailable(container: SurfaceContainer) {
                AALogger.i("onSurfaceAvailable: width=${container.width}, height=${container.height}, density=${container.dpi}")
                cancelPendingRecreate("new surface available")
                lastContainer = container
                createDisplayFromContainer(container)
                AALogger.shareableCopy()
            }

            override fun onSurfaceDestroyed(container: SurfaceContainer) {
                AALogger.i("onSurfaceDestroyed")
                cancelPendingRecreate("surface destroyed")
                lastContainer = null
                carDisplay.destroy()
                // Copy log to /sdcard for easy retrieval after session ends
                AALogger.shareableCopy()
            }

            override fun onVisibleAreaChanged(visibleArea: Rect) {
                AALogger.d("onVisibleAreaChanged: $visibleArea")
                // If the visible area is smaller than before, the head unit may
                // have opened system UI. We keep the full surface but let the
                // activity adapt via configuration change.
            }

            override fun onStableAreaChanged(stableArea: Rect) {
                AALogger.d("onStableAreaChanged: $stableArea")
                val newW = stableArea.width()
                val newH = stableArea.height()
                if (newW <= 0 || newH <= 0) return

                val (w, h) = carDisplay.currentDimensions()

                // No display yet — create immediately (no debounce needed).
                if (w <= 0 || h <= 0) {
                    AALogger.i("Stable area ${newW}x${newH} with no live display — creating now")
                    recreateDisplay(newW, newH)
                    AALogger.shareableCopy()
                    return
                }

                // Ignore identical or sub-5% changes — not worth a recreation.
                val dwPct = abs(newW - w) / w.toFloat()
                val dhPct = abs(newH - h) / h.toFloat()
                if ((newW == w && newH == h) || (dwPct < 0.05f && dhPct < 0.05f)) {
                    AALogger.d("Stable area ${newW}x${newH} vs current ${w}x${h} below threshold — ignored")
                    return
                }

                // Debounce: the host often sends a burst of stable-area
                // updates; only recreate once the dims settle for ~700ms.
                cancelPendingRecreate("new area ${newW}x${newH}")
                val task = Runnable {
                    pendingRecreate = null
                    val (cw, ch) = carDisplay.currentDimensions()
                    AALogger.i("Debounce fired: recreating VirtualDisplay at ${newW}x${newH} (was ${cw}x${ch})")
                    recreateDisplay(newW, newH)
                    AALogger.shareableCopy()
                }
                pendingRecreate = task
                mainHandler.postDelayed(task, 700L)
                AALogger.i("Recreation debounce scheduled in 700ms for ${newW}x${newH} (was ${w}x${h})")
                AALogger.shareableCopy()
            }

            // -- Gesture callbacks ------------------------------------------

            @Suppress("DEPRECATION")
            @androidx.car.app.annotations.ExperimentalCarApi
            override fun onScroll(distanceX: Float, distanceY: Float) {
                carDisplay.dispatchScroll(distanceX, distanceY)
            }

            @Suppress("DEPRECATION")
            @androidx.car.app.annotations.ExperimentalCarApi
            override fun onFling(velocityX: Float, velocityY: Float) {
                carDisplay.dispatchFling(velocityX, velocityY)
            }

            @Suppress("DEPRECATION")
            @androidx.car.app.annotations.ExperimentalCarApi
            override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
                carDisplay.dispatchScale(scaleFactor, focusX, focusY)
            }

            @Suppress("DEPRECATION")
            @androidx.car.app.annotations.ExperimentalCarApi
            override fun onClick(x: Float, y: Float) {
                carDisplay.dispatchClick(x, y)
            }

            // -- Internal helper --------------------------------------------

            private fun createDisplayFromContainer(container: SurfaceContainer) {
                val success = carDisplay.create(container)
                if (!success) {
                    AALogger.e("Failed to create VirtualDisplay — map will not render")
                }
            }

            /** Cancel a scheduled recreation, if any. */
            private fun cancelPendingRecreate(reason: String) {
                pendingRecreate?.let {
                    mainHandler.removeCallbacks(it)
                    pendingRecreate = null
                    AALogger.i("Recreation debounce cancelled ($reason)")
                }
            }

            /**
             * Recreate the VirtualDisplay at the given dimensions on the last
             * known surface. [CarDisplay.create] finishes the old activity
             * and releases the previous display internally.
             */
            private fun recreateDisplay(width: Int, height: Int) {
                val container = lastContainer
                if (container == null) {
                    AALogger.w("Cannot recreate VirtualDisplay at ${width}x${height}: no surface container")
                    return
                }
                val surface: Surface = container.surface ?: return
                // Use the container's own dpi (not carContext phone metrics).
                carDisplay.create(surface, width, height, container.dpi)
            }
        }
    }

    private companion object {
        /** Wakelock safety timeout, renewed every maintenance cycle. */
        private const val WAKELOCK_TIMEOUT_MS = 4 * 60 * 60 * 1000L

        /** How often to re-power-off the panel and renew the wakelock. */
        private const val PANEL_REOFF_INTERVAL_MS = 30_000L
    }
}
