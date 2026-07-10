package com.benjagest.backend.accounting;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * F1-BANCO (2026-07-10) — Lector MÍNIMO de .xlsx para extractos bancarios
 * (BBVA no ofrece N43; exporta Excel). Un .xlsx es un ZIP con XML dentro:
 * esto lo lee con {@code java.util.zip} + el parser DOM del JDK, sin meter
 * Apache POI (~15 MB de dependencias) en el instalador.
 *
 * <p>Soporta lo que un extracto real necesita: sharedStrings (celdas de
 * texto), celdas numéricas, inlineStr, y celdas ausentes (se rellenan por
 * la referencia de columna de cada celda, p.ej. r="C7"). No soporta
 * fórmulas ni estilos — un extracto exportado no los trae.
 */
final class XlsxLite {

    private XlsxLite() {}

    /** Filas de la PRIMERA hoja como matriz de strings (celdas vacías = ""). */
    static List<String[]> rows(byte[] xlsx) throws Exception {
        byte[] sharedXml = null;
        byte[] sheetXml = null;
        String firstSheet = null;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String name = e.getName();
                if ("xl/sharedStrings.xml".equals(name)) {
                    sharedXml = zis.readAllBytes();
                } else if (name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml")) {
                    // Nos quedamos con la primera hoja en orden natural.
                    if (firstSheet == null || name.compareTo(firstSheet) < 0) {
                        firstSheet = name;
                        sheetXml = zis.readAllBytes();
                    }
                }
            }
        }
        if (sheetXml == null) throw new IllegalArgumentException("El .xlsx no contiene hojas");

        List<String> shared = new ArrayList<>();
        if (sharedXml != null) {
            NodeList sis = parse(sharedXml).getElementsByTagName("si");
            for (int i = 0; i < sis.getLength(); i++) {
                // Un <si> puede tener varios <t> (texto con formato troceado).
                NodeList ts = ((Element) sis.item(i)).getElementsByTagName("t");
                StringBuilder sb = new StringBuilder();
                for (int j = 0; j < ts.getLength(); j++) sb.append(ts.item(j).getTextContent());
                shared.add(sb.toString());
            }
        }

        List<String[]> out = new ArrayList<>();
        NodeList rows = parse(sheetXml).getElementsByTagName("row");
        for (int i = 0; i < rows.getLength(); i++) {
            Element row = (Element) rows.item(i);
            NodeList cells = row.getElementsByTagName("c");
            List<String> vals = new ArrayList<>();
            for (int j = 0; j < cells.getLength(); j++) {
                Element c = (Element) cells.item(j);
                int col = colIndex(c.getAttribute("r"));
                while (vals.size() <= col) vals.add("");
                vals.set(col, cellValue(c, shared));
            }
            out.add(vals.toArray(new String[0]));
        }
        return out;
    }

    private static String cellValue(Element c, List<String> shared) {
        String type = c.getAttribute("t");
        if ("inlineStr".equals(type)) {
            NodeList ts = c.getElementsByTagName("t");
            return ts.getLength() > 0 ? ts.item(0).getTextContent() : "";
        }
        NodeList vs = c.getElementsByTagName("v");
        if (vs.getLength() == 0) return "";
        String raw = vs.item(0).getTextContent();
        if ("s".equals(type)) {
            try {
                return shared.get(Integer.parseInt(raw.trim()));
            } catch (RuntimeException ex) {
                return "";
            }
        }
        return raw;
    }

    /** "BC12" → índice de columna 0-based (A=0, B=1… BC=54). */
    private static int colIndex(String ref) {
        int col = 0;
        for (int i = 0; i < ref.length(); i++) {
            char ch = ref.charAt(i);
            if (ch < 'A' || ch > 'Z') break;
            col = col * 26 + (ch - 'A' + 1);
        }
        return Math.max(0, col - 1);
    }

    private static Document parse(byte[] xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        // XXE off: el fichero viene de fuera (banco/usuario).
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setExpandEntityReferences(false);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }
}
