package com.ccs.wiredroid.util;

import java.io.StringReader;
import java.io.StringWriter;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.xml.sax.InputSource;

public final class XmlFormatter {

    private XmlFormatter() {}

    public static String formatXml(String unformattedXml) {
        if (unformattedXml == null || unformattedXml.trim().isEmpty()) {
            return unformattedXml;
        }

        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setValidating(false);
            dbf.setNamespaceAware(true);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(new InputSource(new StringReader(unformattedXml.trim())));

            TransformerFactory tf = TransformerFactory.newInstance();
            Transformer transformer = tf.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");

            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(doc), new StreamResult(writer));
            return writer.toString();
        } catch (Exception e) {
            // Fallback line-by-line indenter
            return fallbackLineFormat(unformattedXml);
        }
    }

    private static String fallbackLineFormat(String input) {
        String[] lines = input.split("\n");
        StringBuilder sb = new StringBuilder();
        int indentLevel = 0;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            if (trimmed.startsWith("</")) {
                indentLevel = Math.max(0, indentLevel - 1);
            }

            for (int i = 0; i < indentLevel; i++) {
                sb.append("    ");
            }
            sb.append(trimmed).append("\n");

            if (trimmed.startsWith("<") && !trimmed.startsWith("</") && !trimmed.startsWith("<?")
                    && !trimmed.startsWith("<!--") && !trimmed.endsWith("/>") && !trimmed.contains("</")) {
                indentLevel++;
            }
        }
        return sb.toString().trim() + "\n";
    }
}
