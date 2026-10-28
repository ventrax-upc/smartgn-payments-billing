package com.smartgn.billing.adapter;

import org.w3c.dom.*;

import java.io.*;
import java.util.zip.*;

import javax.xml.parsers.*;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

public final class XmlSupport {
    private XmlSupport() {}

    public static Document parse(byte[] bytes) {
        try {
            var f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return f.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid XML", e);
        }
    }

    public static byte[] bytes(Document doc) {
        try {
            var t = TransformerFactory.newInstance();
            t.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
            t.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            var tr = t.newTransformer();
            tr.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            var out = new ByteArrayOutputStream();
            tr.transform(new DOMSource(doc), new StreamResult(out));
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String escape(Object v) {
        return String.valueOf(v)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace(String.valueOf((char) 34), "&quot;")
                .replace("'", "&apos;");
    }

    public static byte[] zip(String name, byte[] xml) {
        try {
            var out = new ByteArrayOutputStream();
            try (var z = new ZipOutputStream(out)) {
                var entry = new ZipEntry(name);
                entry.setTime(0);
                z.putNextEntry(entry);
                z.write(xml);
                z.closeEntry();
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] unzipXml(byte[] zip) {
        if (zip.length > 2_000_000) throw new IllegalArgumentException("CDR too large");
        try (var z = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                if (e.getName().endsWith(".xml")) {
                    byte[] b = z.readNBytes(2_000_001);
                    if (b.length > 2_000_000) throw new IllegalArgumentException("CDR too large");
                    return b;
                }
            }
            throw new IllegalArgumentException("CDR XML missing");
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid CDR ZIP", e);
        }
    }

    public static String first(Document d, String local) {
        var nodes = d.getElementsByTagNameNS("*", local);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent();
    }
}
