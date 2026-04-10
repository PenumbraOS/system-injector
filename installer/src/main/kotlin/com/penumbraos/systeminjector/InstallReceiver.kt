package com.penumbraos.systeminjector

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.FileUtils
import android.system.Os
import android.util.Log
import java.io.File
import java.util.zip.ZipFile

/**
 * Broadcast receiver for the installer (runs as UID 1000 in system_server).
 *
 * Receives: com.penumbraos.systeminjector.INSTALL
 * With the extra: apk_path (String); the path to the APK to install.
 *
 * Flow:
 *   1. Patch manifest (add sharedUserId)
 *   2. Re-sign with embedded keystore
 *   3. Verify cert matches TARGET_CERT_HEX
 *   4. Copy signed APK to /data/app/<dirname>/base.apk
 *   5. Inject package entry into packages.xml
 *   6. Write packages-backup.xml
 *   7. Kill system_server (triggers reboot, PMS reads new packages-backup.xml)
 */
class InstallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SystemInjector"
        const val ACTION_INSTALL = "com.penumbraos.systeminjector.INSTALL"
        const val EXTRA_APK_PATH = "apk_path"

        /** ABI string to instruction set name, matching VMRuntime.ABI_TO_INSTRUCTION_SET_MAP */
        private val ABI_TO_INSTRUCTION_SET = mapOf(
            "arm64-v8a" to "arm64",
            "armeabi-v7a" to "arm",
            "x86" to "x86",
            "x86_64" to "x86_64"
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL) return

        val apkPath = intent.getStringExtra(EXTRA_APK_PATH)
        if (apkPath.isNullOrBlank()) {
            Log.e(TAG, "Missing apk_path extra")
            return
        }

        Thread {
            try {
                installFrom(context, File(apkPath))
            } catch (e: SecurityException) {
                // Safety abort — cert mismatch, do NOT proceed
                Log.e(TAG, "SAFETY ABORT: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Install failed", e)
            }
        }.start()
    }

    /** Install an APK file. */
    fun installFrom(context: Context, inputApk: File) {
        Log.i(TAG, "Starting install of ${inputApk.absolutePath}")

        if (!inputApk.exists()) {
            Log.e(TAG, "APK not found: ${inputApk.absolutePath}")
            return
        }

        val workDir = File(context.cacheDir, "patch_work")
        val result = ApkPatcher.patch(
            inputApk = inputApk,
            assetOpener = context.assets::open,
            workDir = workDir
        )

        Log.i(TAG, "Patched package: ${result.packageName}")

        // If the package is already installed, uninstall it first
        if (isPackageInstalled(context, result.packageName)) {
            Log.i(TAG, "Package ${result.packageName} already installed, uninstalling...")
            val proc = ProcessBuilder("pm", "uninstall", result.packageName).start()
            proc.waitFor()
            val exitCode = proc.exitValue()
            check(exitCode == 0) {
                "pm uninstall ${result.packageName} failed with exit code $exitCode"
            }
            Log.i(TAG, "Existing package uninstalled")
        }

        // Step 4: Copy to /data/app/
        val appDirName = "${result.packageName}-injected"
        val appDir = File("/data/app/$appDirName")
        appDir.mkdirs()
        Os.chmod(appDir.absolutePath, 505) // 0771 octal = 505 decimal (rwxrwx--x)

        val targetApk = File(appDir, "base.apk")
        result.signedApk.inputStream().use { input ->
            targetApk.outputStream().use { output ->
                FileUtils.copy(input, output)
            }
        }

        // Set correct permissions (system:system, 0644)
        Os.chmod(targetApk.absolutePath, 420) // 0644 octal = 420 decimal
        Log.i(TAG, "APK copied to ${targetApk.absolutePath}")

        // Extract native libraries from APK to appDir/lib/<ISA>/
        val primaryCpuAbi = extractNativeLibs(targetApk, appDir)
        if (primaryCpuAbi != null) {
            Log.i(TAG, "Extracted native libs for ABI: $primaryCpuAbi")
        }

        SignatureInjector.inject(
            packageName = result.packageName,
            codePath = appDir.absolutePath,
            sharedUserId = 1000,
            primaryCpuAbi = primaryCpuAbi
        )
        Log.i(TAG, "packages-backup.xml written")

        // Clean up
        workDir.deleteRecursively()
        inputApk.delete()

        // Kill system_server to trigger reboot
        Log.i(TAG, "Killing system_server to apply changes...")
        Os.kill(Os.getpid(), 9)
    }

    /**
     * Extract native libraries from an APK to appDir/lib/<ISA>/.
     *
     * Scans the APK zip for entries matching lib/<abi>/\*.so and extracts them
     * to the on-disk layout PMS expects for cluster installs:
     *   <appDir>/lib/<instructionSet>/<name>.so
     *
     * For example, lib/arm64-v8a/liblsplant.so -> <appDir>/lib/arm64/liblsplant.so
     *
     * @param apkFile The APK file to extract from (must already be on disk)
     * @param appDir The app install directory (e.g. /data/app/com.example-injected)
     * @return The ABI string (e.g. "arm64-v8a") if native libs were found, null otherwise
     */
    private fun extractNativeLibs(apkFile: File, appDir: File): String? {
        var detectedAbi: String? = null

        ZipFile(apkFile).use { zip ->
            for (entry in zip.entries()) {
                if (entry.isDirectory) continue
                val name = entry.name

                // Match lib/<abi>/<something>.so
                if (!name.startsWith("lib/") || !name.endsWith(".so")) continue
                val parts = name.split("/")
                if (parts.size != 3) continue

                val abi = parts[1]
                val soName = parts[2]
                val instructionSet = ABI_TO_INSTRUCTION_SET[abi] ?: continue

                // Use the first ABI we encounter
                if (detectedAbi == null) {
                    detectedAbi = abi
                }

                // Only extract for the first ABI (don't mix ABIs)
                if (abi != detectedAbi) continue

                val libDir = File(appDir, "lib/$instructionSet")
                libDir.mkdirs()
                Os.chmod(libDir.parentFile!!.absolutePath, 493) // 0755
                Os.chmod(libDir.absolutePath, 493) // 0755

                val outFile = File(libDir, soName)
                zip.getInputStream(entry).use { input ->
                    outFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Os.chmod(outFile.absolutePath, 493) // 0755
                Log.i(TAG, "Extracted: $name -> ${outFile.absolutePath}")
            }
        }

        return detectedAbi
    }

    private fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            false
        }
    }
}
