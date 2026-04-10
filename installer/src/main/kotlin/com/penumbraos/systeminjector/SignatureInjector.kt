package com.penumbraos.systeminjector

import com.penumbraos.systeminjector.common.PackagesXmlPatcher
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Injects a package entry into packages.xml so PMS recognizes a new system UID app.
 */
object SignatureInjector {

    /**
     * Inject a package into packages.xml and write packages-backup.xml.
     *
     * @param packageName The package name to inject (e.g. "com.example.myapp")
     * @param codePath The path where the APK is installed (e.g. "/data/app/~~hash/com.example.myapp-xxx")
     * @param sharedUserId The shared UID to assign
     * @param primaryCpuAbi The primary CPU ABI (e.g. "arm64-v8a") if native libs are present, null otherwise
     */
    fun inject(packageName: String, codePath: String, sharedUserId: Int = 1000, primaryCpuAbi: String? = null) {
        val abxData = File("/data/system/packages.xml").readBytes()
        val xmlText = abx2xml(abxData)

        check(xmlText.isNotEmpty()) { "ABX conversion produced empty output" }

        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(xmlText.byteInputStream())
        check(document.documentElement.nodeName == "packages") {
            "packages.xml root element is '${document.documentElement.nodeName}', expected 'packages'"
        }

        PackagesXmlPatcher.insertPackage(document, packageName, codePath, sharedUserId, primaryCpuAbi)

        val outputBytes = PackagesXmlPatcher.serializeAndValidate(document)

        File("/data/system/packages-backup.xml").outputStream().use { out ->
            out.write(outputBytes)
        }
    }
}
