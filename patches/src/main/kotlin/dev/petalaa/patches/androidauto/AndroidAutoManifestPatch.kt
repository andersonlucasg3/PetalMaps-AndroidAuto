package dev.petalaa.patches.androidauto

import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element

/**
 * Resource patch that edits AndroidManifest.xml to declare the Android Auto CarAppService,
 * required meta-data, and permissions.
 *
 * Idempotent: will not duplicate entries if already present.
 */
@Suppress("unused")
val androidAutoManifestPatch = resourcePatch(
    name = "Android Auto Manifest",
    description = "Adds the Android Auto CarAppService declaration, meta-data, " +
            "and permissions to AndroidManifest.xml.",
) {
    // Target: com.huawei.maps.app 4.7.0.322(001) — versionCode 40700322
    @Suppress("DEPRECATION")
    compatibleWith("com.huawei.maps.app" to setOf("4.7.0.322(001)"))

    execute {
        document("AndroidManifest.xml").use { doc ->
            val applicationNode = doc
                .getElementsByTagName("application")
                .item(0) as Element

            // --- Meta-data ---

            fun addMetaDataIfMissing(name: String, value: String) {
                val existing = applicationNode.getElementsByTagName("meta-data")
                for (i in 0 until existing.length) {
                    val node = existing.item(i) as? Element ?: continue
                    if (node.getAttribute("android:name") == name) return // already present
                }
                val meta = doc.createElement("meta-data")
                meta.setAttribute("android:name", name)
                meta.setAttribute("android:value", value)
                applicationNode.appendChild(meta)
            }

            addMetaDataIfMissing(
                "com.google.android.gms.car.application",
                "@xml/automotive_app_desc"
            )
            addMetaDataIfMissing(
                "androidx.car.app.minCarApiLevel",
                "1"
            )

            // --- Service declaration ---

            val manifestRoot = doc.documentElement
            val existingServices = manifestRoot.getElementsByTagName("service")
            var serviceExists = false
            for (i in 0 until existingServices.length) {
                val svc = existingServices.item(i) as? Element ?: continue
                if (svc.getAttribute("android:name") == "dev.petalaa.extension.PetalCarAppService") {
                    serviceExists = true
                    break
                }
            }

            if (!serviceExists) {
                val serviceNode = doc.createElement("service")
                serviceNode.setAttribute("android:name", "dev.petalaa.extension.PetalCarAppService")
                serviceNode.setAttribute("android:exported", "true")

                val intentFilter = doc.createElement("intent-filter")
                val action = doc.createElement("action")
                action.setAttribute("android:name", "androidx.car.app.CarAppService")
                intentFilter.appendChild(action)

                val category = doc.createElement("category")
                category.setAttribute("android:name", "androidx.car.app.category.NAVIGATION")
                intentFilter.appendChild(category)

                serviceNode.appendChild(intentFilter)
                applicationNode.appendChild(serviceNode)
            }

            // --- Permission ---

            fun addPermissionIfMissing(name: String) {
                val existing = manifestRoot.getElementsByTagName("uses-permission")
                for (i in 0 until existing.length) {
                    val perm = existing.item(i) as? Element ?: continue
                    if (perm.getAttribute("android:name") == name) return
                }
                val permNode = doc.createElement("uses-permission")
                permNode.setAttribute("android:name", name)
                // Insert before <application> node for cleanliness
                manifestRoot.insertBefore(permNode, applicationNode)
            }

            addPermissionIfMissing("androidx.car.app.NAVIGATION_TEMPLATES")
        }
    }
}
