package dev.petalaa.patches.androidauto

import app.morphe.patcher.patch.resourcePatch
import dev.petalaa.patches.androidauto.Constants.COMPATIBILITY_PETAL_MAPS
import org.w3c.dom.Element

/**
 * Resource patch that changes AutoPetalMapsActivity's screenOrientation from
 * "landscape" to "unspecified".
 *
 * Petal Maps declares this activity with android:screenOrientation="landscape".
 * When rendered on a VirtualDisplay (Android Auto projection), the system
 * rotates the window artificially (touch input ends up inverted 180°) and
 * applies letterboxing to the portrait layout. "unspecified" lets the activity
 * follow the display orientation (our VirtualDisplay is landscape).
 *
 * Idempotent: no-op if the attribute is missing or already "unspecified".
 */
@Suppress("unused")
val automotiveOrientationPatch = resourcePatch(
    name = "Automotive orientation fix",
    description = "Changes AutoPetalMapsActivity's screenOrientation from " +
            "landscape to unspecified so it follows the VirtualDisplay orientation.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_PETAL_MAPS)

    execute {
        document("AndroidManifest.xml").use { doc ->
            val activityName = "com.huawei.maps.auto.activity.AutoPetalMapsActivity"
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

                orientation == "unspecified" ->
                    println("Automotive orientation fix: $activityName already has screenOrientation=\"unspecified\" — no-op")

                else -> {
                    activity.setAttribute("android:screenOrientation", "unspecified")
                    println("Automotive orientation fix: $activityName screenOrientation \"$orientation\" -> \"unspecified\"")
                }
            }
        }
    }
}
