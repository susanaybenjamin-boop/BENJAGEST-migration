package com.benjagest.backend.billing.verifactu;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Service;

/**
 * Reproduce la formula de hash SHA-256 segun la especificacion VeriFactu
 * de la AEAT. Replicada fielmente de la implementacion CONTENDO en
 * `verifactuService.js` (validada en produccion contra AEAT).
 *
 * Formato de la cadena (orden y separador "&" obligatorios):
 *
 *   IDEmisorFactura={NIF EN MAYUSCULAS}
 *   &NumSerieFactura={NUMERO TAL CUAL}
 *   &FechaExpedicionFactura={DD-MM-YYYY}
 *   &TipoFactura=F1
 *   &CuotaTotal={IVA TOTAL CON 2 DECIMALES}
 *   &ImporteTotal={IMPORTE TOTAL CON 2 DECIMALES}
 *   &Huella={HASH ANTERIOR EN MAYUSCULAS}
 *   &FechaHoraHusoGenRegistro={YYYY-MM-DDTHH:MM:SS+01:00}
 *
 * Notas criticas (de CONTENDO):
 *   - AEAT compara hashes en MAYUSCULAS.
 *   - El primer registro de la cadena lleva Huella vacia (la cadena
 *     vacia se incluye en el hash; quitarla cambia el resultado).
 *   - Los importes se formatean con `toFixed(2)` JS (HALF_UP a 2
 *     decimales).
 *   - TipoFactura: F1 completa (default), F2 simplificada, R1-R5
 *     rectificativas (bloque RECT 2026-07-07). El tipo usado se guarda
 *     en verifactu_registry.invoice_type_code al emitir; NULL historico
 *     equivale a F1 y el canonical antiguo no cambia ni un byte.
 *   - Los offsets horarios deben seguir hora oficial de Espana
 *     (+01:00 invierno, +02:00 verano). Aqui usamos la zona del
 *     servidor; en produccion conviene forzar Europe/Madrid.
 */
@Service
public class VerifactuHashService {

    private static final DateTimeFormatter FECHA_EXPEDICION = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final DateTimeFormatter FECHA_HORA_HUSO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx");

    /**
     * Calcula la huella (hash) VeriFactu de una factura encadenando con
     * el hash anterior de la misma cadena (company + modo).
     *
     * @param nifEmisor        NIF/CIF de la empresa emisora (se pone en mayusculas).
     * @param invoiceNumber    Numero legal de la factura (tal cual, sin espacios).
     * @param invoiceDate      Fecha de expedicion (DD-MM-YYYY).
     * @param vatTotal         CuotaTotal (suma de IVA de las lineas).
     * @param total            ImporteTotal (total de la factura).
     * @param previousHash     Hash del registro anterior de la cadena (vacio si es el primero).
     * @param generationTime   Fecha/hora de generacion del registro (instante del validate).
     * @return                 Huella SHA-256 hex en MAYUSCULAS (64 chars).
     */
    public String computeHash(String nifEmisor,
                              String invoiceNumber,
                              LocalDate invoiceDate,
                              BigDecimal vatTotal,
                              BigDecimal total,
                              String previousHash,
                              OffsetDateTime generationTime) {
        return computeHash(nifEmisor, invoiceNumber, invoiceDate, vatTotal, total,
                previousHash, generationTime, null, null, null);
    }

    /** Sobrecarga TPB-4 previa al bloque RECT — delega con tipo F1. */
    public String computeHash(String nifEmisor,
                              String invoiceNumber,
                              LocalDate invoiceDate,
                              BigDecimal vatTotal,
                              BigDecimal total,
                              String previousHash,
                              OffsetDateTime generationTime,
                              String thirdPartyNif,
                              String thirdPartyName) {
        return computeHash(nifEmisor, invoiceNumber, invoiceDate, vatTotal, total,
                previousHash, generationTime, thirdPartyNif, thirdPartyName, null);
    }

    /**
     * TPB-4 — datos del tercero expedidor: si {@code thirdPartyNif}
     * viene non-null, extiende el canonical con
     * &EmitidaPorTercero=T&TerceroNif&TerceroNombre.
     *
     * RECT — {@code invoiceTypeCode}: TipoFactura del registro (F1
     * completa, F2 simplificada, R1-R5 rectificativas). Cuando es null o
     * vacío se emite F1 — así la verificación de cadenas históricas
     * (verifactu_registry.invoice_type_code NULL) recalcula EXACTAMENTE
     * el mismo canonical de siempre. El tipo usado se persiste al emitir
     * y la verificación usa el valor guardado, nunca lo re-deriva.
     */
    public String computeHash(String nifEmisor,
                              String invoiceNumber,
                              LocalDate invoiceDate,
                              BigDecimal vatTotal,
                              BigDecimal total,
                              String previousHash,
                              OffsetDateTime generationTime,
                              String thirdPartyNif,
                              String thirdPartyName,
                              String invoiceTypeCode) {
        if (nifEmisor == null || nifEmisor.isBlank()) {
            throw new IllegalArgumentException("nifEmisor requerido");
        }
        if (invoiceNumber == null || invoiceNumber.isBlank()) {
            throw new IllegalArgumentException("invoiceNumber requerido");
        }
        if (invoiceDate == null) {
            throw new IllegalArgumentException("invoiceDate requerido");
        }
        if (generationTime == null) {
            throw new IllegalArgumentException("generationTime requerido");
        }

        String cuotaTotal = (vatTotal == null ? BigDecimal.ZERO : vatTotal)
                .setScale(2, RoundingMode.HALF_UP)
                .toPlainString();
        String importeTotal = (total == null ? BigDecimal.ZERO : total)
                .setScale(2, RoundingMode.HALF_UP)
                .toPlainString();
        String previous = previousHash == null ? "" : previousHash.toUpperCase();

        String tipoFactura = (invoiceTypeCode == null || invoiceTypeCode.isBlank())
                ? "F1" : invoiceTypeCode.trim().toUpperCase();
        String chain = String.join("&",
                "IDEmisorFactura=" + nifEmisor.trim().toUpperCase(),
                "NumSerieFactura=" + invoiceNumber.trim(),
                "FechaExpedicionFactura=" + invoiceDate.format(FECHA_EXPEDICION),
                "TipoFactura=" + tipoFactura,
                "CuotaTotal=" + cuotaTotal,
                "ImporteTotal=" + importeTotal,
                "Huella=" + previous,
                "FechaHoraHusoGenRegistro=" + generationTime.format(FECHA_HORA_HUSO)
        );

        // TPB-4: solo se añade si hay tercero expedidor real. Para
        // facturas no-TPB el canonical queda exactamente como antes,
        // las cadenas históricas siguen verificables.
        if (thirdPartyNif != null && !thirdPartyNif.isBlank()) {
            chain = chain
                    + "&EmitidaPorTercero=T"
                    + "&TerceroNif=" + thirdPartyNif.trim().toUpperCase()
                    + "&TerceroNombre=" + (thirdPartyName == null ? "" : thirdPartyName.trim());
        }

        return sha256Hex(chain).toUpperCase();
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 no disponible", ex);
        }
    }
}
