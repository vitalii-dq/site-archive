package org.archive.sitearchive.sitemap;

import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the page locations from a {@code <urlset>} sitemap
 * (<a href="https://www.sitemaps.org/protocol.html">sitemaps.org protocol</a>).
 * <p>
 * Only {@code <loc>} elements that are direct children of {@code <url>} in the sitemap namespace count.
 * Extensions in other namespaces, such as {@code <image:loc>} or {@code <xhtml:link>}, are ignored.
 */
@Component
public class SitemapParser {

    private static final String SITEMAP_NAMESPACE = "http://www.sitemaps.org/schemas/sitemap/0.9";

    private final XMLInputFactory xmlInputFactory = createSecureFactory();

    /** Returns the {@code <loc>} values of all {@code <url>} entries in document order. */
    public List<String> parseLocations(byte[] xml) throws SitemapException {
        XMLStreamReader reader = null;
        try {
            reader = xmlInputFactory.createXMLStreamReader(new ByteArrayInputStream(xml));
            reader.nextTag();
            String rootNamespace = namespaceOf(reader);
            if (reader.getLocalName().equals("sitemapindex")) {
                throw new SitemapException("The URL points to a sitemap index, which is not supported. "
                        + "Submit one of the sitemaps it lists instead.", false);
            }
            if (!reader.getLocalName().equals("urlset") || !isSitemapNamespace(rootNamespace)) {
                throw new SitemapException("The document is not a sitemap: expected a <urlset> root element "
                        + "in the " + SITEMAP_NAMESPACE + " namespace", false);
            }
            return readLocations(reader, rootNamespace);
        } catch (XMLStreamException e) {
            // DOCTYPE declarations end up here too, because they are not allowed before the root element.
            throw new SitemapException("The sitemap is not valid XML: " + e.getMessage().replaceAll("\\s+", " "), false);
        } finally {
            close(reader);
        }
    }

    private static List<String> readLocations(XMLStreamReader reader, String namespace) throws XMLStreamException {
        List<String> locations = new ArrayList<>();
        int depth = 1; // positioned on <urlset>
        boolean inUrlEntry = false;
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
                if (depth == 2) {
                    inUrlEntry = isElement(reader, namespace, "url");
                } else if (depth == 3 && inUrlEntry && isElement(reader, namespace, "loc")) {
                    locations.add(reader.getElementText().strip());
                    depth--; // getElementText() consumed the matching end tag
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
        return locations;
    }

    private static boolean isElement(XMLStreamReader reader, String namespace, String localName) {
        return reader.getLocalName().equals(localName) && namespaceOf(reader).equals(namespace);
    }

    private static String namespaceOf(XMLStreamReader reader) {
        return reader.getNamespaceURI() == null ? "" : reader.getNamespaceURI();
    }

    // Documents without any namespace are accepted too: they are a common, harmless deviation.
    private static boolean isSitemapNamespace(String namespace) {
        return namespace.equals(SITEMAP_NAMESPACE) || namespace.isEmpty();
    }

    private static XMLInputFactory createSecureFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        // Sitemaps never need a DTD. Disabling DTDs and external entities prevents XXE and entity expansion attacks.
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return factory;
    }

    private static void close(XMLStreamReader reader) {
        if (reader == null) {
            return;
        }
        try {
            reader.close();
        } catch (XMLStreamException ignored) {
            // nothing left to release
        }
    }
}
