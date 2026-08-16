package dev.petalaa.extension

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate

/**
 * The main map [Screen] rendered on the Android Auto head unit.
 *
 * This screen returns a bare [NavigationTemplate] — no action strip —
 * so the map surface occupies the whole screen. Zoom and recenter are
 * driven by gestures (pinch, click) dispatched through [CarDisplay];
 * the actual surface rendering is managed by [PetalSession] via
 * [AppManager.setSurfaceCallback].
 *
 * @param carContext The car context provided by the framework.
 * @param carDisplay Helper that dispatches synthetic touch events
 *                   (scroll, fling, pinch, click) to the projected map.
 */
class MapScreen(
    carContext: CarContext,
    private val carDisplay: CarDisplay
) : Screen(carContext) {

    init {
        AALogger.i("MapScreen created")
    }

    override fun onGetTemplate(): Template {
        AALogger.i("onGetTemplate")
        return NavigationTemplate.Builder()
            .build()
    }
}
