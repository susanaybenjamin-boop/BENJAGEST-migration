package com.benjagest.backend.billing.verifactu;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * VF3-XML (2026-07-12) — Construye el {@code RegistroAlta} de VeriFactu segun
 * el XSD OFICIAL de AEAT (SuministroInformacion.xsd), listo para ir dentro del
 * envelope SOAP {@code RegFactuSistemaFacturacion} que envia
 * {@link AeatVerifactuClient}.
 *
 * <h2>Por que un builder puro</h2>
 * Clase SIN estado ni dependencias Spring, como {@link VerifactuHashService}:
 * entra un {@link Input} con los datos ya resueltos, sale el XML. Asi se puede
 * fijar con un test GOLDEN (XML esperado byte a byte) SIN certificado ni envio.
 *
 * <h2>Orden de elementos (obligatorio segun XSD)</h2>
 * El XSD valida el orden de la secuencia. Este builder respeta el orden del
 * {@code RegistroFacturacionAltaType}:
 * <ol>
 *   <li>IDVersion (1.0)</li>
 *   <li>IDFactura (IDEmisorFactura, NumSerieFactura, FechaExpedicionFactura)</li>
 *   <li>NombreRazonEmisor</li>
 *   <li>TipoFactura (F1/F2/R1-R5)</li>
 *   <li>DescripcionOperacion</li>
 *   <li>Destinatarios (0..1; se omite en F2 sin destinatario)</li>
 *   <li>Desglose (por tipo de IVA)</li>
 *   <li>CuotaTotal, ImporteTotal</li>
 *   <li>Encadenamiento (PrimerRegistro=S o RegistroAnterior)</li>
 *   <li>SistemaInformatico (identidad del SIF)</li>
 *   <li>FechaHoraHusoGenRegistro</li>
 *   <li>TipoHuella (01 = SHA-256), Huella</li>
 * </ol>
 *
 * <h2>Coherencia con la huella</h2>
 * Los valores que tambien entran en el canonical de {@link VerifactuHashService}
 * (fecha DD-MM-YYYY, importes con 2 decimales, huella en mayusculas) se
 * presentan aqui EXACTAMENTE igual, para que el XML y la huella cuadren.
 *
 * <h2>Pendiente (siguientes slices, ver docs/backlog VF3)</h2>
 * <ul>
 *   <li>VF3-SIF: los valores de {@link SistemaInformatico} son la IDENTIDAD del
 *       SIF ante AEAT — los decide Benjamin y requieren el alta del SIF.</li>
 *   <li>Bloques de rectificativa (TipoRectificativa, FacturasRectificadas,
 *       ImporteRectificacion) para R1-R5.</li>
 *   <li>Exenciones/no-sujeciones en el Desglose (OperacionExenta E1-E6,
 *       CalificacionOperacion N1/N2). Hoy el builder emite el caso comun
 *       "sujeta y no exenta" (S1) con TipoImpositivo + CuotaRepercutida.</li>
 * </ul>
 */
public final class AeatRegistroAltaXmlBuilder {

    /** Namespace de SuministroInformacion.xsd (donde vive RegistroAlta y sus hijos). */
    public static final String NS_SF =
            "https://www2.agenciatributaria.gob.es/static_files/common/internet/dep/"
            + "aplicaciones/es/aeat/tike/cont/ws/SuministroInformacion.xsd";

    private static final DateTimeFormatter FECHA_EXPEDICION = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final DateTimeFormatter FECHA_HORA_HUSO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx");

    private AeatRegistroAltaXmlBuilder() {}

    /** Un destinatario de la factura (para F1; F2 puede no llevar). */
    public record Destinatario(String nombreRazon, String nif) {}

    /** Una linea del Desglose por tipo de IVA (caso comun: sujeta y no exenta). */
    public record Desglose(String claveRegimen,        // 01 = regimen general
                           String calificacionOperacion, // S1 = sujeta y no exenta
                           String tipoImpositivo,       // p.ej. "21.00"
                           String baseImponible,        // 2 decimales
                           String cuotaRepercutida) {}  // 2 decimales

    /** Encadenamiento con el registro anterior de la cadena. Null => PrimerRegistro. */
    public record RegistroAnterior(String emisorNif, String numSerie,
                                   String fechaExpedicion, String huella) {}

    /** Identidad del Sistema Informatico de Facturacion (VF3-SIF, decide Benjamin). */
    public record SistemaInformatico(String nombreRazon, String nif,
                                     String nombreSistemaInformatico, String idSistemaInformatico,
                                     String version, String numeroInstalacion) {}

    /** Todos los datos ya resueltos del RegistroAlta. */
    public record Input(String emisorNif, String numSerie, LocalDate fechaExpedicion,
                        String nombreRazonEmisor, String tipoFactura, String descripcionOperacion,
                        List<Destinatario> destinatarios, List<Desglose> desglose,
                        String cuotaTotal, String importeTotal,
                        RegistroAnterior registroAnterior,
                        SistemaInformatico sistemaInformatico,
                        OffsetDateTime fechaHoraHusoGenRegistro, String huella) {}

    /**
     * Devuelve el XML del elemento {@code <RegistroAlta>} (con su namespace por
     * defecto), listo para incrustar en {@code <RegistroFactura>} del SOAP.
     */
    public static String build(Input in) {
        StringBuilder sb = new StringBuilder();
        sb.append("<RegistroAlta xmlns=\"").append(NS_SF).append("\">");

        sb.append("<IDVersion>1.0</IDVersion>");

        sb.append("<IDFactura>");
        el(sb, "IDEmisorFactura", up(in.emisorNif()));
        el(sb, "NumSerieFactura", in.numSerie());
        el(sb, "FechaExpedicionFactura", in.fechaExpedicion().format(FECHA_EXPEDICION));
        sb.append("</IDFactura>");

        el(sb, "NombreRazonEmisor", in.nombreRazonEmisor());
        el(sb, "TipoFactura", in.tipoFactura());
        el(sb, "DescripcionOperacion", in.descripcionOperacion());

        // Destinatarios (0..1). En F2 sin destinatario, se omite.
        if (in.destinatarios() != null && !in.destinatarios().isEmpty()) {
            sb.append("<Destinatarios>");
            for (Destinatario d : in.destinatarios()) {
                sb.append("<IDDestinatario>");
                el(sb, "NombreRazon", d.nombreRazon());
                // NIF nacional; para no-residentes iria IDOtro (pendiente).
                el(sb, "NIF", up(d.nif()));
                sb.append("</IDDestinatario>");
            }
            sb.append("</Destinatarios>");
        }

        // Desglose (1..1) — hasta 12 lineas por tipo de IVA.
        sb.append("<Desglose>");
        for (Desglose g : in.desglose()) {
            sb.append("<DetalleDesglose>");
            el(sb, "ClaveRegimen", g.claveRegimen());
            el(sb, "CalificacionOperacion", g.calificacionOperacion());
            el(sb, "TipoImpositivo", g.tipoImpositivo());
            el(sb, "BaseImponibleOimporteNoSujeto", g.baseImponible());
            el(sb, "CuotaRepercutida", g.cuotaRepercutida());
            sb.append("</DetalleDesglose>");
        }
        sb.append("</Desglose>");

        el(sb, "CuotaTotal", in.cuotaTotal());
        el(sb, "ImporteTotal", in.importeTotal());

        // Encadenamiento (1..1).
        sb.append("<Encadenamiento>");
        RegistroAnterior prev = in.registroAnterior();
        if (prev == null) {
            el(sb, "PrimerRegistro", "S");
        } else {
            sb.append("<RegistroAnterior>");
            el(sb, "IDEmisorFactura", up(prev.emisorNif()));
            el(sb, "NumSerieFactura", prev.numSerie());
            el(sb, "FechaExpedicionFactura", prev.fechaExpedicion());
            el(sb, "Huella", up(prev.huella()));
            sb.append("</RegistroAnterior>");
        }
        sb.append("</Encadenamiento>");

        // SistemaInformatico (1..1).
        SistemaInformatico si = in.sistemaInformatico();
        sb.append("<SistemaInformatico>");
        el(sb, "NombreRazon", si.nombreRazon());
        el(sb, "NIF", up(si.nif()));
        el(sb, "NombreSistemaInformatico", si.nombreSistemaInformatico());
        el(sb, "IdSistemaInformatico", si.idSistemaInformatico());
        el(sb, "Version", si.version());
        el(sb, "NumeroInstalacion", si.numeroInstalacion());
        sb.append("</SistemaInformatico>");

        el(sb, "FechaHoraHusoGenRegistro", in.fechaHoraHusoGenRegistro().format(FECHA_HORA_HUSO));
        el(sb, "TipoHuella", "01"); // 01 = SHA-256
        el(sb, "Huella", up(in.huella()));

        sb.append("</RegistroAlta>");
        return sb.toString();
    }

    private static void el(StringBuilder sb, String name, String value) {
        sb.append('<').append(name).append('>')
          .append(escape(value))
          .append("</").append(name).append('>');
    }

    private static String up(String s) {
        return s == null ? "" : s.trim().toUpperCase();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
