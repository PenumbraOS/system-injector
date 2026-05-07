package com.penumbraos.systeminjector

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * ContentProvider that stages APK files for installation.
 *
 * Because the installer runs inside system_server (UID 1000), it cannot read
 * files from /data/local/tmp (SELinux: system_server denied open on
 * shell_data_file). And returning a raw file FD to the caller doesn't work
 * either — the caller (adb shell, running as `shell` domain) gets denied
 * write to `system_data_file`.
 *
 * The solution: return a **pipe** FD. The caller writes bytes into the pipe
 * (SELinux allows shell to write to pipes). A background thread inside
 * system_server reads from the pipe and writes to the actual file on disk
 * (SELinux allows system_server to write to its own data files).
 *
 * Authority: com.penumbraos.systeminjector.staging
 *
 * Usage from CLI (two steps):
 *
 *   # 1. Stage the APK (pipes bytes through Binder into system_server's cache)
 *   adb shell content write \
 *     --uri content://com.penumbraos.systeminjector.staging/foo.apk \
 *     < foo.apk
 *
 *   # 2. Trigger install of the staged file
 *   adb shell content call \
 *     --uri content://com.penumbraos.systeminjector.staging \
 *     --method install --arg foo.apk
 */
class StagingProvider : ContentProvider() {

    companion object {
        private const val TAG = "SystemInjector"
        const val AUTHORITY = "com.penumbraos.systeminjector.staging"
        const val RESULT_MESSAGE = "message"
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            context!!.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun stagingDir(): File {
        val dir = File(context!!.cacheDir, "system-injector-staging")
        dir.mkdirs()
        return dir
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val filename = uri.lastPathSegment
            ?: throw IllegalArgumentException("URI must end with a filename")

        require(!filename.contains("/") && !filename.contains("..")) {
            "Invalid filename: $filename"
        }

        val file = File(stagingDir(), filename)

        Log.w(TAG, "StagingProvider: openFile $filename (mode=$mode)")

        if (!mode.contains("w")) {
            // Read mode: return a pipe that feeds the file's contents to the caller
            val pipe = ParcelFileDescriptor.createReliablePipe()
            val readEnd = pipe[0]  // returned to caller
            val writeEnd = pipe[1] // we write file contents into this

            Thread {
                try {
                    FileOutputStream(writeEnd.fileDescriptor).use { out ->
                        file.inputStream().use { input ->
                            input.copyTo(out)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "StagingProvider: error reading $filename", e)
                } finally {
                    writeEnd.close()
                }
            }.start()

            return readEnd
        }

        // Write mode: return a pipe; system_server drains it to disk
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val readEnd = pipe[0]  // we read from this inside system_server
        val writeEnd = pipe[1] // returned to caller (shell)

        Thread {
            try {
                FileInputStream(readEnd.fileDescriptor).use { input ->
                    file.outputStream().use { out ->
                        input.copyTo(out)
                    }
                }
                Log.w(TAG, "StagingProvider: staged $filename (${file.length()} bytes)")
            } catch (e: Exception) {
                Log.e(TAG, "StagingProvider: error writing $filename", e)
            } finally {
                readEnd.close()
            }
        }.start()

        return writeEnd
    }

    /**
     * Handle `content call --method install --arg <filename>`.
     *
     * Checks if the package ID is already installed, then delegates to [InstallReceiver.installFrom] on a
     * background thread (the install kills system_server at the end).
     */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != "install") {
            Log.e(TAG, "StagingProvider: unknown method '$method'")
            return Bundle().apply {
                putString(RESULT_MESSAGE, "Unknown method: $method")
            }
        }

        val filename = arg
        if (filename.isNullOrBlank()) {
            Log.e(TAG, "StagingProvider: install called without filename arg")
            return Bundle().apply {
                putString(RESULT_MESSAGE, "Missing install filename")
            }
        }

        require(!filename.contains("/") && !filename.contains("..")) {
            "Invalid filename: $filename"
        }

        val stagedApk = File(stagingDir(), filename)
        if (!stagedApk.exists()) {
            Log.e(TAG, "StagingProvider: staged file not found: ${stagedApk.absolutePath}")
            return Bundle().apply {
                putString(RESULT_MESSAGE, "Staged file not found: $filename")
            }
        }

        val packageName = try {
            ApkPatcher.extractPackageName(stagedApk)
        } catch (e: Exception) {
            Log.e(TAG, "Failed reading package name from staged APK", e)
            return Bundle().apply {
                putString(RESULT_MESSAGE, e.message ?: "Failed reading package name")
            }
        }

        if (isPackageInstalled(packageName)) {
            Log.w(TAG, "Duplicate package detected for $packageName")
            return Bundle().apply {
                putString(RESULT_MESSAGE, "DUPLICATE_PACKAGE:$packageName")
            }
        }

        Log.w(TAG, "StagingProvider: triggering install of ${stagedApk.absolutePath}")

        Thread {
            try {
                InstallReceiver().installFrom(context!!, stagedApk)
            } catch (e: SecurityException) {
                Log.e(TAG, "SAFETY ABORT: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Install failed", e)
            }
        }.start()

        return Bundle().apply {
            putString(RESULT_MESSAGE, "OK")
        }
    }

    // Required overrides — not used
    override fun onCreate(): Boolean = true
    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = "application/vnd.android.package-archive"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
