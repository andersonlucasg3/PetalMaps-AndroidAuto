/*
 * Copyright 2026 PetalAA.
 * https://github.com/petalaa/PetalMaps-AndroidAuto
 *
 * Adapted from the Morphe patches project.
 *
 * This file is part of the PetalAA patches project and is licensed under
 * the GNU General Public License version 3 (GPLv3).
 *
 * https://www.gnu.org/licenses/gpl-3.0.html
 */

package dev.petalaa.patches.androidauto

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import dev.petalaa.patches.androidauto.Constants.COMPATIBILITY_PETAL_MAPS

/**
 * Fingerprint for the AccountFactory method that decides which sign-in flow
 * to return.
 *
 * Original body: `return up2.g(a81.c()) ? a.g() : b.n();`
 * Patched body: checks HMS Core availability at runtime:
 *   - HMS Core installed  → returns a.g() (HwPhoneAccountHelper via HMS)
 *   - HMS Core missing    → returns b.n() (AccountPicker with WebView H5)
 *
 * This method is structurally unique: it is the only static method in the
 * APK that returns AccountApi with no parameters AND calls
 * `Lcom/huawei/maps/businessbase/utils/account/b;->n()`.
 */
internal object D4AFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Lcom/huawei/maps/businessbase/utils/account/AccountApi;",
    parameters = emptyList(),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/huawei/maps/businessbase/utils/account/b;",
            name = "n",
            parameters = emptyList(),
            returnType = "Lcom/huawei/maps/businessbase/utils/account/b;",
            opcode = Opcode.INVOKE_STATIC,
        )
    ),
)

/**
 * Bytecode patch that makes [d4.a] check HMS Core availability at runtime
 * and choose the correct sign-in path.
 *
 * ## Problem
 * [ManufacturerCheckBypassPatch] forces [up2.g] → true, which makes [d4.a]
 * always return the Huawei ID Auth flow ([a.g]). On devices without HMS Core,
 * that flow cannot show any UI (HwIdSignInHubActivity checks HMS availability
 * and finishes immediately with no WebView fallback).
 *
 * ## Fix
 * Replace [d4.a] to check HMS Core availability via
 * [AvailableAdapter.isHuaweiMobileServicesAvailable]:
 * - Returns 0 (HMS available)  → use [a.g] (HwPhoneAccountHelper via HMS Core)
 * - Returns non-zero (HMS missing) → use [b.n] (AccountPicker with WebView H5)
 *
 * This way devices WITH HMS Core get the full HMS sign-in experience, while
 * devices WITHOUT HMS Core fall back to the WebView-based login.
 *
 * ## Compatibility
 * Coexists safely with [ManufacturerCheckBypassPatch]: the login path
 * handles HMS detection directly, while the rest of the app still uses the
 * manufacturer bypass for splash-screen gating.
 *
 * @see docs/login-analysis.md for the full login-flow analysis.
 */
@Suppress("unused")
val huaweiLoginFixPatch = bytecodePatch(
    name = "Huawei login fix",
    description = "Checks HMS Core availability at runtime: uses HMS sign-in " +
            "when HMS Core is installed, falls back to AccountPicker WebView " +
            "when HMS Core is missing.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_PETAL_MAPS)

    execute {
        val method = D4AFingerprint.method
        val instructionCount = method.implementation?.instructions?.size ?: return@execute

        // Clear the original body (invoke up2.g → if-eqz → a.g() or b.n())
        if (instructionCount > 0) {
            method.removeInstructions(0, instructionCount)
        }

        // Replace with HMS Core availability check:
        //   Context ctx = a81.c();
        //   AvailableAdapter adapter = new AvailableAdapter(0);
        //   int result = adapter.isHuaweiMobileServicesAvailable(ctx);
        //   if (result == 0) return a.g();   // HMS Core available
        //   return b.n();                     // HMS Core missing → WebView
        method.addInstructions(
            0,
            """
                invoke-static {}, La81;->c()Landroid/content/Context;
                move-result-object v0
                new-instance v1, Lcom/huawei/hms/adapter/AvailableAdapter;
                const/4 v2, 0x0
                invoke-direct {v1, v2}, Lcom/huawei/hms/adapter/AvailableAdapter;-><init>(I)V
                invoke-virtual {v1, v0}, Lcom/huawei/hms/adapter/AvailableAdapter;->isHuaweiMobileServicesAvailable(Landroid/content/Context;)I
                move-result v0
                if-eqz v0, :hms_missing
                invoke-static {}, Lcom/huawei/maps/businessbase/utils/account/a;->g()Lcom/huawei/maps/businessbase/utils/account/a;
                move-result-object v0
                return-object v0
                :hms_missing
                invoke-static {}, Lcom/huawei/maps/businessbase/utils/account/b;->n()Lcom/huawei/maps/businessbase/utils/account/b;
                move-result-object v0
                return-object v0
            """
        )
    }
}