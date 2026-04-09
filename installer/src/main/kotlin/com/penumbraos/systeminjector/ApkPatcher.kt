package com.penumbraos.systeminjector

import android.util.Log
import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import com.penumbraos.systeminjector.common.SigningConstants
import com.wind.meditor.core.FileProcesser
import com.wind.meditor.property.AttributeItem
import com.wind.meditor.property.ModificationProperty
import com.wind.meditor.utils.NodeValue
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * Patches an APK to run as system UID:
 * 1. Adds android:sharedUserId="android.uid.system" to the manifest
 * 2. Re-signs the APK with the embedded keystore
 * 3. Verifies the signature matches TARGET_CERT_HEX
 */
object ApkPatcher {

    private const val TAG = "ApkPatcher"
    private const val KEYSTORE_ASSET = "abxdroppedapk.keystore"
    private const val KEYSTORE_PASSWORD = "abxdroppedapk"
    private const val KEY_ALIAS = "abxdroppedapk"

    /**
     * Result of a successful patch operation.
     * @param signedApk The path to the signed, patched APK (temp file)
     * @param packageName The package name extracted from the APK
     */
    data class PatchResult(
        val signedApk: File,
        val packageName: String
    )

    /**
     * Patch an APK to be a system UID app.
     *
     * @param inputApk Path to the original APK
     * @param assetOpener Function to open an asset by name (from Context.assets::open)
     * @param workDir Temporary directory for intermediate files
     * @return PatchResult on success
     * @throws SecurityException if the signed APK's certificate doesn't match TARGET_CERT_HEX
     * @throws Exception on any other failure
     */
    fun patch(
        inputApk: File,
        assetOpener: (String) -> java.io.InputStream,
        workDir: File
    ): PatchResult {
        workDir.mkdirs()

        val packageName = extractPackageName(inputApk)
        Log.i(TAG, "Extracted package name: $packageName")

        val patchedApk = File(workDir, "patched.apk")
        patchManifest(inputApk, patchedApk)
        Log.i(TAG, "Manifest patched: added sharedUserId")

        val signedApk = File(workDir, "signed.apk")
        sign(patchedApk, signedApk, assetOpener)
        Log.i(TAG, "APK re-signed")

        verify(signedApk)
        Log.i(TAG, "Signature verified: matches TARGET_CERT_HEX")

        // Clean up intermediate file
        patchedApk.delete()

        return PatchResult(signedApk, packageName)
    }

    /**
     * Extract package name using Android framework's PackageParser (hidden API).
     * Available on Android 12 (API 31/32).
     */
    private fun extractPackageName(apk: File): String {
        @Suppress("DEPRECATION")
        val parserClass = Class.forName("android.content.pm.PackageParser")
        val parser = parserClass.getDeclaredConstructor().newInstance()
        val parseMethod = parserClass.getMethod("parsePackage", File::class.java, Int::class.javaPrimitiveType)
        val pkg = parseMethod.invoke(parser, apk, 0)
        val packageNameField = pkg.javaClass.getField("packageName")
        return packageNameField.get(pkg) as String
    }

    /**
     * Patch the APK's manifest to add android:sharedUserId="android.uid.system".
     *
     * Uses ManifestEditor's FileProcesser which:
     * - Re-zips the APK with the modified manifest
     * - Strips existing signature files (META-INF .SF, .RSA, etc.)
     */
    private fun patchManifest(inputApk: File, outputApk: File) {
        val property = ModificationProperty()
            .addManifestAttribute(
                // Note: NodeValue.Manifest.SHARDE_USER_ID is the library's constant (typo is theirs)
                AttributeItem(NodeValue.Manifest.SHARDE_USER_ID, "android.uid.system")
            )

        FileProcesser.processApkFile(
            inputApk.absolutePath,
            outputApk.absolutePath,
            property
        )
    }

    /**
     * Re-sign the APK with v1 (JAR) signing using the embedded keystore.
     */
    private fun sign(inputApk: File, outputApk: File, assetOpener: (String) -> java.io.InputStream) {
        val keyStore = KeyStore.getInstance("PKCS12")
        assetOpener(KEYSTORE_ASSET).use { ks ->
            keyStore.load(ks, KEYSTORE_PASSWORD.toCharArray())
        }

        val privateKey = keyStore.getKey(KEY_ALIAS, KEYSTORE_PASSWORD.toCharArray()) as PrivateKey
        val certChain = keyStore.getCertificateChain(KEY_ALIAS)
            .map { it as X509Certificate }

        val signerConfig = ApkSigner.SignerConfig.Builder(
            KEY_ALIAS,
            privateKey,
            certChain
        ).build()

        val signer = ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(inputApk)
            .setOutputApk(outputApk)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(false)
            .setV3SigningEnabled(false)
            .build()

        signer.sign()
    }

    /**
     * Verify that the signed APK's certificate matches TARGET_CERT_HEX.
     *
     * @throws SecurityException if the certificate doesn't match
     */
    private fun verify(signedApk: File) {
        val result = ApkVerifier.Builder(signedApk).build().verify()

        if (!result.isVerified) {
            val errors = result.errors.joinToString("; ") { it.toString() }
            throw SecurityException("APK verification failed: $errors")
        }

        val signerCerts = result.signerCertificates
        if (signerCerts.isEmpty()) {
            throw SecurityException("APK has no signer certificates")
        }

        // Get the DER-encoded certificate and convert to hex
        val certHex = signerCerts[0].encoded.joinToString("") { "%02x".format(it) }

        if (certHex != SigningConstants.TARGET_CERT_HEX) {
            throw SecurityException(
                "Certificate mismatch! Expected TARGET_CERT_HEX but got different cert. Aborting"
            )
        }
    }
}
