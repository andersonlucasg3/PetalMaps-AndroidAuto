package dev.petalaa.patches.androidauto

import app.morphe.patcher.patch.resourcePatch
import dev.petalaa.patches.androidauto.Constants.COMPATIBILITY_PETAL_MAPS
import org.w3c.dom.Element

/**
 * Resource patch that changes PetalMapsActivity's screenOrientation from
 * "behind" to "sensorLandscape".
 *
 * Petal Maps declares the main activity with android:screenOrientation="behind",
 * so on the car display it follows the orientation of the activity behind it
 * and renders in portrait. "sensorLandscape" fixes it to landscape in both
 * directions without forcing a specific rotation (avoids the inverted 180°
 * touch input).
 *
 * Idempotent: no-op if the attribute is missing or already "sensorLandscape".
 */
@Suppress("unused")
val automotiveOrientationPatch = resourcePatch(
    name = "Automotive orientation fix (main activity)",
    description = "Changes PetalMapsActivity's screenOrientation from " +
            "behind to sensorLandscape so it renders in landscape on the car display.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_PETAL_MAPS)

    execute {
        document("AndroidManifest.xml").use { doc ->
            val activityName = "com.huawei.maps.app.petalmaps.PetalMapsActivity"
            val activities = doc.getElementsByTagName("activity")
            val activity = (0 until activities.length)
                .asSequence()
                .mapNotNull { activities.item(it) as? Element }
                .firstOrNull { it.getAttribute("android:name") == activityName }

            if (activity == null) {
                println("Automotive orientation fix: $activityName not found in manifest — no-op")
                return@use
            }

            val orientation = activity.getAttribute("android:screenOrientation")
            when {
                orientation.isEmpty() ->
                    println("Automotive orientation fix: $activityName has no android:screenOrientation — no-op")

                orientation == "sensorLandscape" ->
                    println("Automotive orientation fix: $activityName already has screenOrientation=\"sensorLandscape\" — no-op")

                else -> {
                    activity.setAttribute("android:screenOrientation", "sensorLandscape")
                    println("Automotive orientation fix: $activityName screenOrientation \"$orientation\" -> \"sensorLandscape\"")
                }
            }
        }
    }
}
