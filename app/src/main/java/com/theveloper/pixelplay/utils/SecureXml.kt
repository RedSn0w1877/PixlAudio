package com.theveloper.pixelplay.utils

import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.xml.sax.EntityResolver
import org.xml.sax.InputSource

/**
 * DOM parsing for untrusted lyrics XML (TTML imports and online catalogs).
 *
 * Android's built-in `DocumentBuilderFactory` throws `ParserConfigurationException` for every
 * feature except namespaces and validation, so hardening that relies on `setFeature` alone
 * either silently fails (the whole parse is lost) or is not applied. Instead:
 * - any document type declaration is refused before parsing, so no internal or external
 *   entity can ever be defined (no XXE, no "billion laughs");
 * - each JVM hardening feature is still requested, individually, where the platform has it;
 * - external entities resolve to nothing and entity references are not expanded.
 */
internal object SecureXml {

    private val DTD_MARKER = Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE)

    fun hasDocumentTypeDeclaration(xml: String): Boolean = DTD_MARKER.containsMatchIn(xml)

    fun newDocumentBuilder(): DocumentBuilder {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isValidating = false
            runCatching { isXIncludeAware = false }
            runCatching { isExpandEntityReferences = false }
            listOf(
                XMLConstants.FEATURE_SECURE_PROCESSING to true,
                "http://apache.org/xml/features/disallow-doctype-decl" to true,
                "http://xml.org/sax/features/external-general-entities" to false,
                "http://xml.org/sax/features/external-parameter-entities" to false,
                "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
            ).forEach { (feature, value) -> runCatching { setFeature(feature, value) } }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
        }
        return factory.newDocumentBuilder().apply {
            setEntityResolver(EntityResolver { _, _ -> InputSource(StringReader("")) })
        }
    }

    /** Parses [xml], or returns `null` for a DTD-bearing, malformed or otherwise unusable document. */
    fun parse(xml: String): Document? {
        if (hasDocumentTypeDeclaration(xml)) return null
        return runCatching { newDocumentBuilder().parse(InputSource(StringReader(xml))) }.getOrNull()
    }
}
