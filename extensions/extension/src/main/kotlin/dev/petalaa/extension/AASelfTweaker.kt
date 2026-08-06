package dev.petalaa.extension

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors

/**
 * Self-registration tweaker for the Android Auto allowlist.
 *
 * Called (via bytecode patch) from `MapApplication.onCreate()` of the host
 * Petal Maps APK. On every invocation it verifies — against a root copy of
 * the GMS phenotype database — whether our own package is already present in
 * the `app_white_list` flag override. If it is, nothing happens; otherwise
 * the full registration flow runs.
 *
 * ## Safety contract
 *
 * - All work happens on a background single-thread executor, never on the
 *   main thread (this is invoked from `Application.onCreate`).
 * - Every failure is swallowed and logged under the `PetalAA` tag. This
 *   object must NEVER crash the host app, regardless of root state, GMS
 *   version, or database schema drift.
 * - Idempotent and self-healing: the database is the single source of truth.
 *   No blind SharedPreferences caching — if GMS wipes the overrides, the
 *   next call re-applies them.
 */
object AASelfTweaker {

    private const val TAG = "PetalAA"

    private const val GMS_PACKAGE = "com.google.android.gms"
    private const val GMS_CAR_PACKAGE = "com.google.android.gms.car"
    private const val GEARHEAD_PACKAGE = "com.google.android.projection.gearhead"
    private const val PHENOTYPE_DB_PATH =
        "/data/data/com.google.android.gms/databases/phenotype.db"
    private const val WORK_DB_NAME = "phenotype_work.db"

    private const val FLAG_APP_WHITE_LIST = "app_white_list"
    private const val FLAG_BROADCAST_WHITELIST = "car_connect_broadcast_whitelist"

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PetalAA-Tweaker").apply { isDaemon = true }
    }

    /**
     * Entry point patched into `MapApplication.onCreate()`. Dispatches the
     * check/registration to a background thread and returns immediately.
     *
     * PUBLIC CONTRACT — do not change this signature.
     */
    @JvmStatic
    fun ensureRegistered(context: Context) {
        val appContext = context.applicationContext
        executor.execute {
            try {
                checkAndRegister(appContext)
            } catch (t: Throwable) {
                // Never propagate: the host app must survive any failure here.
                Log.e(TAG, "ensureRegistered: unexpected failure", t)
            }
        }
    }

    // ---- Core flow ---------------------------------------------------------

    private fun checkAndRegister(context: Context) {
        // 1. Root check: `su -c id` must report uid=0.
        val (idExit, idOut, idErr) = runSu("id")
        if (idExit != 0 || !idOut.contains("uid=0")) {
            Log.w(TAG, "Root not available (exit=$idExit, out='$idOut', err='$idErr') — skipping")
            return
        }

        val ourPackage = context.packageName
        Log.i(TAG, "Root OK. Checking allowlist for package '$ourPackage'")

        // 2. Kill GMS first so it does not rewrite the db while we work on it.
        runSu("am kill all $GMS_PACKAGE")

        // 3. Snapshot uid/gid of the original db for later restoration.
        val (statExit, statOut, _) = runSu("stat -c '%u %g' $PHENOTYPE_DB_PATH")
        val ownerIds = statOut.trim().split(Regex("\\s+"))
        if (statExit != 0 || ownerIds.size < 2) {
            Log.w(TAG, "Could not stat phenotype.db (exit=$statExit, out='$statOut') — skipping")
            return
        }
        val uid = ownerIds[0]
        val gid = ownerIds[1]

        // 4. Copy the db (plus WAL/SHM if present) into our private dir.
        val workDb = File(context.filesDir, WORK_DB_NAME)
        if (!copyDbToWorkDir(workDb)) {
            Log.w(TAG, "Failed to copy phenotype.db to work dir — skipping")
            return
        }

        var success = false
        try {
            // 5. Open the COPY read/write. Never touch the original directly.
            val db = SQLiteDatabase.openDatabase(
                workDb.absolutePath, null, SQLiteDatabase.OPEN_READWRITE
            )
            try {
                if (!tableExists(db, "FlagOverrides")) {
                    Log.w(TAG, "FlagOverrides table not found — unsupported GMS schema, skipping")
                    return
                }

                if (isPackageWhitelisted(db, ourPackage)) {
                    Log.i(TAG, "'$ourPackage' already in $FLAG_APP_WHITE_LIST — nothing to do")
                    return
                }

                Log.i(TAG, "'$ourPackage' missing from allowlist — registering")
                applyFlagOverrides(db, ourPackage)

                // 6. Checkpoint the WAL so all changes live in the main db file
                //    before we copy it back over the original.
                runCatching {
                    db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
                }
            } finally {
                runCatching { db.close() }
            }

            // 7. Push the patched copy back over the original.
            val (cpExit, _, cpErr) = runSu("cp ${workDb.absolutePath} $PHENOTYPE_DB_PATH")
            if (cpExit != 0) {
                Log.e(TAG, "Failed to copy patched db back: $cpErr")
                return
            }

            // 8. Restore ownership and SELinux context so GMS can open the db.
            runSu("chown $uid:$gid $PHENOTYPE_DB_PATH")
            val (rcExit, _, rcErr) = runSu("restorecon $PHENOTYPE_DB_PATH")
            if (rcExit != 0) {
                // restorecon may not exist on some ROMs — not fatal.
                Log.d(TAG, "restorecon failed/absent (exit=$rcExit): $rcErr")
            }

            // 9. Restart Android Auto so it re-reads the flags.
            runSu("am force-stop $GEARHEAD_PACKAGE")

            success = true
        } catch (t: Throwable) {
            Log.e(TAG, "Registration flow failed", t)
        } finally {
            runCatching { workDb.delete() }
            runCatching { File(workDb.absolutePath + "-wal").delete() }
            runCatching { File(workDb.absolutePath + "-shm").delete() }
        }

        if (success) {
            Log.i(TAG, "Registered '$ourPackage' in Android Auto allowlist")
            showToast(context, "Petal AA: registered in Android Auto. Reboot may be required.")
        }
    }

    // ---- Database helpers ---------------------------------------------------

    /** Copies phenotype.db (and -wal/-shm when present) into [workDb]. */
    private fun copyDbToWorkDir(workDb: File): Boolean {
        val (cpExit, _, cpErr) = runSu("cp $PHENOTYPE_DB_PATH ${workDb.absolutePath}")
        if (cpExit != 0) {
            Log.e(TAG, "cp of phenotype.db failed: $cpErr")
            return false
        }
        // WAL/SHM companions are optional; ignore failures.
        runSu("cp $PHENOTYPE_DB_PATH-wal ${workDb.absolutePath}-wal")
        runSu("cp $PHENOTYPE_DB_PATH-shm ${workDb.absolutePath}-shm")
        return true
    }

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean {
        db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)
        ).use { return it.moveToFirst() }
    }

    /** True when [pkg] already appears in the `app_white_list` override. */
    private fun isPackageWhitelisted(db: SQLiteDatabase, pkg: String): Boolean {
        val current = readOverrideValue(db, GMS_CAR_PACKAGE, FLAG_APP_WHITE_LIST) ?: return false
        return current.split(',').any { it.trim() == pkg }
    }

    private fun readOverrideValue(db: SQLiteDatabase, packageName: String, name: String): String? {
        return runCatching {
            db.rawQuery(
                "SELECT stringVal FROM FlagOverrides WHERE packageName=? AND name=?",
                arrayOf(packageName, name)
            ).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
        }.getOrElse {
            Log.w(TAG, "Could not read override '$name'", it)
            null
        }
    }

    /**
     * CSV-merge [pkg] into [current]: appends our package only if absent.
     * Never drops packages registered by other apps.
     */
    private fun mergeCsv(current: String?, pkg: String): String {
        if (current.isNullOrBlank()) return pkg
        val entries = current.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (entries.any { it == pkg }) return entries.joinToString(",")
        return (entries + pkg).joinToString(",")
    }

    /**
     * Applies the full set of FlagOverrides rows. String-valued rows merge our
     * package into the existing CSV; boolean rows are unconditional.
     */
    private fun applyFlagOverrides(db: SQLiteDatabase, pkg: String) {
        runCatching { db.execSQL("DROP TRIGGER IF EXISTS aa_patched_apps") }

        // Merged CSV overrides — preserve entries from other apps.
        val mergedWhiteList = mergeCsv(
            readOverrideValue(db, GMS_CAR_PACKAGE, FLAG_APP_WHITE_LIST), pkg
        )
        val mergedBroadcast = mergeCsv(
            readOverrideValue(db, GMS_CAR_PACKAGE, FLAG_BROADCAST_WHITELIST), pkg
        )

        db.execSQL(
            "INSERT OR REPLACE INTO FlagOverrides " +
                "(packageName,flagType,name,user,stringVal,committed) VALUES (?,0,?,?,?,0)",
            arrayOf(GMS_CAR_PACKAGE, FLAG_APP_WHITE_LIST, "", mergedWhiteList)
        )
        db.execSQL(
            "INSERT OR REPLACE INTO FlagOverrides " +
                "(packageName,flagType,name,user,stringVal,committed) VALUES (?,0,?,?,?,0)",
            arrayOf(GMS_CAR_PACKAGE, FLAG_BROADCAST_WHITELIST, "", mergedBroadcast)
        )

        // Empty-string overrides: clear gearhead-side validation lists.
        val emptyStringRows = listOf(
            GEARHEAD_PACKAGE to "AppValidation__allowed_package_list",
            GEARHEAD_PACKAGE to "AppValidation__blocked_packages_by_installer"
        )
        for ((owner, name) in emptyStringRows) {
            db.execSQL(
                "INSERT OR REPLACE INTO FlagOverrides " +
                    "(packageName,flagType,name,user,stringVal,committed) VALUES (?,0,?,'','',0)",
                arrayOf(owner, name)
            )
        }

        // Boolean overrides: bypass validation on both gearhead and gms.car.
        val boolRows = listOf(
            Triple(GEARHEAD_PACKAGE, "AppValidation__should_bypass_validation", 1),
            Triple(GEARHEAD_PACKAGE, "AppValidation__play_install_api", 0),
            Triple(GMS_CAR_PACKAGE, "should_bypass_validation", 1),
            Triple(
                GMS_CAR_PACKAGE,
                "FrameworkCarProjectionValidatorFlags__use_package_manager_api_for_installed_by_play_check",
                0
            ),
            Triple(GEARHEAD_PACKAGE, "UnknownSources__allow_full_screen_apps", 1)
        )
        for ((owner, name, value) in boolRows) {
            db.execSQL(
                "INSERT OR REPLACE INTO FlagOverrides " +
                    "(packageName,flagType,name,user,boolVal,committed) VALUES (?,0,?,'',?,0)",
                arrayOf<Any>(owner, name, value)
            )
        }

        db.execSQL("DELETE FROM Flags WHERE name='app_black_list'")
    }

    // ---- Shell / UI helpers -------------------------------------------------

    /**
     * Runs [command] through `su -c`, returning (exitCode, stdout, stderr).
     * On timeout the process is destroyed and exit code -1 is returned.
     * Never throws.
     */
    private fun runSu(command: String, timeoutSec: Int = 15): Triple<Int, String, String> {
        return try {
            val process = ProcessBuilder("su", "-c", command).start()
            // Drain both streams concurrently to avoid pipe-buffer deadlock.
            var stdout = ""
            var stderr = ""
            val outThread = Thread {
                stdout = process.inputStream.bufferedReader().readText()
            }.apply { isDaemon = true; start() }
            val errThread = Thread {
                stderr = process.errorStream.bufferedReader().readText()
            }.apply { isDaemon = true; start() }
            // Manual wait loop with timeout: Process.waitFor(long, TimeUnit)
            // and destroyForcibly() require API 26, but our minSdk is 23.
            val deadlineMs = System.currentTimeMillis() + timeoutSec * 1000L
            var exitCode: Int? = null
            while (System.currentTimeMillis() < deadlineMs) {
                try {
                    exitCode = process.exitValue()
                    break
                } catch (_: IllegalThreadStateException) {
                    Thread.sleep(50)
                }
            }
            outThread.join(1000)
            errThread.join(1000)
            if (exitCode == null) {
                process.destroy()
                Log.w(TAG, "su command timed out after ${timeoutSec}s: $command")
                Triple(-1, stdout, stderr)
            } else {
                Triple(exitCode, stdout, stderr)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "runSu failed for: $command", t)
            Triple(-1, "", t.message ?: "")
        }
    }

    private fun showToast(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            runCatching {
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }
}
