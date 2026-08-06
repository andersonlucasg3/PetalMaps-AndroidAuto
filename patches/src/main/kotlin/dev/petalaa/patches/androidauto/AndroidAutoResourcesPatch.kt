package dev.petalaa.patches.androidauto

import app.morphe.patcher.patch.resourcePatch

/**
 * Resource patch that adds the required automotive app descriptor XML resource
 * (res/xml/automotive_app_desc.xml) for Android Auto support.
 *
 * This file tells Android Auto which templates the app supports.
 */
@Suppress("unused")
val androidAutoResourcesPatch = resourcePatch(
    name = "Android Auto Resources",
    description = "Adds the automotive_app_desc.xml resource required by Android Auto.",
) {
    @Suppress("DEPRECATION")
    compatibleWith("com.huawei.maps.app" to setOf("4.7.0.322(001)"))

    finalize {
        val descPath = "res/xml/automotive_app_desc.xml"
        val descContent =
            """<?xml version="1.0" encoding="utf-8"?>
<automotiveApp>
    <uses name="template"/>
</automotiveApp>
"""
        // Only write if the file doesn't already exist (idempotent).
        val destFile = this[descPath]
        if (!destFile.exists()) {
            destFile.parentFile?.mkdirs()
            destFile.writeText(descContent)
        }
    }
}
