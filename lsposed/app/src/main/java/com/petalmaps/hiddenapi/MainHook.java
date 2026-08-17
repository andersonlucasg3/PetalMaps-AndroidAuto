package com.petalmaps.hiddenapi;

import android.util.Log;

import org.lsposed.hiddenapibypass.HiddenApiBypass;
import org.lsposed.hiddenapibypass.LSPass;

import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed module that exempts hidden APIs in the Petal Maps process.
 *
 * <p>The extension injected into the Petal Maps APK (com.huawei.maps.app) uses
 * reflection on {@code @hide} APIs (android.window.ScreenCapture,
 * SurfaceControl.screenshot). Hidden API exemptions are process-wide and must
 * be applied inside the target process before those calls happen.
 *
 * <p>Technique: since Android 9, reflective lookup of hidden members is
 * enforced, so the call to {@code dalvik.system.VMRuntime.setHiddenApiExemptions}
 * itself is bootstrapped via {@code org.lsposed.hiddenapibypass}
 * (LSPosed-maintained, Unsafe-based; supports Android 10-17), with LSPass as a
 * fallback. The trailing "L" prefix exempts every hidden API of the process.
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "PetalMapsHiddenApi";
    private static final String TARGET_PACKAGE = "com.huawei.maps.app";

    /**
     * Requested prefixes plus "L" (which already covers the specific ones).
     * Only the "L" prefix is honored on Android 11+; the specific prefixes are
     * kept for API 28-29 where fine-grained exemptions are still meaningful.
     */
    private static final String[] EXEMPTIONS = {
            "Landroid/window/ScreenCapture;",
            "Landroid/window/DisplayCaptureArgs;",
            "Landroid/window/ScreenCaptureResult;",
            "Landroid/view/SurfaceControl;",
            "L"
    };

    /** setHiddenApiExemptions is only effective once per process. */
    private static volatile boolean applied;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }
        if (applied) {
            return;
        }
        applied = true;
        applyHiddenApiExemptions(lpparam.processName);
    }

    private static void applyHiddenApiExemptions(String processName) {
        Log.i(TAG, "Process " + processName + ": applying hidden API exemptions");

        boolean ok = false;
        try {
            ok = HiddenApiBypass.setHiddenApiExemptions(EXEMPTIONS);
        } catch (Throwable t) {
            Log.w(TAG, "HiddenApiBypass failed; falling back to LSPass", t);
        }
        if (!ok) {
            try {
                ok = LSPass.setHiddenApiExemptions(EXEMPTIONS);
            } catch (Throwable t) {
                Log.w(TAG, "LSPass failed too", t);
            }
        }

        Log.i(TAG, "setHiddenApiExemptions: " + (ok ? "OK" : "FAILED"));
        verifyExemption();
    }

    /**
     * Probe: with the exemption active, Class#getDeclaredMethods stops
     * filtering hidden members (e.g. SurfaceControl.screenshot).
     */
    private static void verifyExemption() {
        try {
            Class<?> surfaceControl = Class.forName("android.view.SurfaceControl");
            boolean hasScreenshot = false;
            for (Method m : surfaceControl.getDeclaredMethods()) {
                if ("screenshot".equals(m.getName())) {
                    hasScreenshot = true;
                    break;
                }
            }
            Log.i(TAG, "Verification: SurfaceControl.screenshot via reflection "
                    + (hasScreenshot ? "ACCESSIBLE" : "BLOCKED"));
        } catch (Throwable t) {
            Log.w(TAG, "Verification failed", t);
        }
    }
}
