package com.benjagest.backend.billing.verifactu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;

/**
 * VF3-XML — test GOLDEN del builder del RegistroAlta contra el XSD oficial.
 * Fija el XML esperado y cubre las ramas de encadenamiento y F2.
 */
class AeatRegistroAltaXmlBuilderTest {

    private static AeatRegistroAltaXmlBuilder.SistemaInformatico sif() {
        return new AeatRegistroAltaXmlBuilder.SistemaInformatico(
                "BENJAGEST SL", "B12345678", "BENJAGEST", "01", "0.1.35", "001");
    }

    private static AeatRegistroAltaXmlBuilder.Input baseInput(
            AeatRegistroAltaXmlBuilder.RegistroAnterior prev,
            List<AeatRegistroAltaXmlBuilder.Destinatario> destinatarios,
            String tipoFactura) {
        return new AeatRegistroAltaXmlBuilder.Input(
                "B12345678", "FA2026/0001", LocalDate.of(2026, 7, 12),
                "BENJAGEST SL", tipoFactura, "Servicios de asesoria",
                destinatarios,
                List.of(new AeatRegistroAltaXmlBuilder.Desglose(
                        "01", "S1", "21.00", "100.00", "21.00")),
                "21.00", "121.00",
                prev, sif(),
                OffsetDateTime.of(2026, 7, 12, 10, 30, 0, 0, ZoneOffset.ofHours(2)),
                "abc123def456"); // en minusculas a proposito: el builder la sube
    }

    @Test
    void goldenPrimerRegistroF1() {
        AeatRegistroAltaXmlBuilder.Input in = baseInput(
                null,
                List.of(new AeatRegistroAltaXmlBuilder.Destinatario("Cliente Ejemplo SL", "A87654321")),
                "F1");

        String ns = AeatRegistroAltaXmlBuilder.NS_SF;
        String expected =
                "<RegistroAlta xmlns=\"" + ns + "\">"
                + "<IDVersion>1.0</IDVersion>"
                + "<IDFactura>"
                + "<IDEmisorFactura>B12345678</IDEmisorFactura>"
                + "<NumSerieFactura>FA2026/0001</NumSerieFactura>"
                + "<FechaExpedicionFactura>12-07-2026</FechaExpedicionFactura>"
                + "</IDFactura>"
                + "<NombreRazonEmisor>BENJAGEST SL</NombreRazonEmisor>"
                + "<TipoFactura>F1</TipoFactura>"
                + "<DescripcionOperacion>Servicios de asesoria</DescripcionOperacion>"
                + "<Destinatarios>"
                + "<IDDestinatario>"
                + "<NombreRazon>Cliente Ejemplo SL</NombreRazon>"
                + "<NIF>A87654321</NIF>"
                + "</IDDestinatario>"
                + "</Destinatarios>"
                + "<Desglose>"
                + "<DetalleDesglose>"
                + "<ClaveRegimen>01</ClaveRegimen>"
                + "<CalificacionOperacion>S1</CalificacionOperacion>"
                + "<TipoImpositivo>21.00</TipoImpositivo>"
                + "<BaseImponibleOimporteNoSujeto>100.00</BaseImponibleOimporteNoSujeto>"
                + "<CuotaRepercutida>21.00</CuotaRepercutida>"
                + "</DetalleDesglose>"
                + "</Desglose>"
                + "<CuotaTotal>21.00</CuotaTotal>"
                + "<ImporteTotal>121.00</ImporteTotal>"
                + "<Encadenamiento><PrimerRegistro>S</PrimerRegistro></Encadenamiento>"
                + "<SistemaInformatico>"
                + "<NombreRazon>BENJAGEST SL</NombreRazon>"
                + "<NIF>B12345678</NIF>"
                + "<NombreSistemaInformatico>BENJAGEST</NombreSistemaInformatico>"
                + "<IdSistemaInformatico>01</IdSistemaInformatico>"
                + "<Version>0.1.35</Version>"
                + "<NumeroInstalacion>001</NumeroInstalacion>"
                + "</SistemaInformatico>"
                + "<FechaHoraHusoGenRegistro>2026-07-12T10:30:00+02:00</FechaHoraHusoGenRegistro>"
                + "<TipoHuella>01</TipoHuella>"
                + "<Huella>ABC123DEF456</Huella>"
                + "</RegistroAlta>";

        assertEquals(expected, AeatRegistroAltaXmlBuilder.build(in));
    }

    @Test
    void registroAnteriorSustituyeAPrimerRegistro() {
        AeatRegistroAltaXmlBuilder.RegistroAnterior prev =
                new AeatRegistroAltaXmlBuilder.RegistroAnterior(
                        "B12345678", "FA2026/0000", "01-07-2026", "deadbeef");
        String xml = AeatRegistroAltaXmlBuilder.build(baseInput(prev,
                List.of(new AeatRegistroAltaXmlBuilder.Destinatario("X", "A87654321")), "F1"));

        assertFalse(xml.contains("<PrimerRegistro>"), "no debe llevar PrimerRegistro si hay anterior");
        assertTrue(xml.contains("<RegistroAnterior>"));
        assertTrue(xml.contains("<Huella>DEADBEEF</Huella>"), "la huella anterior va en mayusculas");
        assertTrue(xml.contains("<NumSerieFactura>FA2026/0000</NumSerieFactura>"));
    }

    @Test
    void f2SinDestinatarioOmiteElBloque() {
        String xml = AeatRegistroAltaXmlBuilder.build(baseInput(null, List.of(), "F2"));
        assertFalse(xml.contains("<Destinatarios>"), "F2 sin destinatario no lleva el bloque");
        assertTrue(xml.contains("<TipoFactura>F2</TipoFactura>"));
    }

    @Test
    void esXmlBienFormado() {
        String xml = AeatRegistroAltaXmlBuilder.build(baseInput(null,
                List.of(new AeatRegistroAltaXmlBuilder.Destinatario("Cli & Co <S.L.>", "A87654321")), "F1"));
        assertDoesNotThrow(() -> {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        });
    }
}
