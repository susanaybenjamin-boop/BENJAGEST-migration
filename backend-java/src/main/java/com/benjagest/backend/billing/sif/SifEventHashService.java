package com.benjagest.backend.billing.sif;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Service;

/**
 * Calcula la huella SHA-256 encadenada de un evento del SIF.
 *
 * Formato canonico (orden y separador "&" obligatorios):
 *
 *   IDEmisor={NIF EN MAYUSCULAS}
 *   &TipoEvento={CODIGO}
 *   &Payload={STRING corto, vacio si null}
 *   &Huella={HASH ANTERIOR EN MAYUSCULAS}
 *   &FechaHoraHusoGenRegistro={YYYY-MM-DDTHH:MM:SS+01:00}
 *
 * Decisiones:
 *   - El primer evento de la cadena lleva Huella="" (igual que facturas).
 *   - El hash devuelto en MAYUSCULAS hex (64 chars).
 *   - El payload se incluye literal — para no romper la cadena cuando
 *     un payload contiene "&" o "=", lo escapamos minimamente
 *     (reemplazamos & por & y = por = antes del hash).
 *
 * Aviso: el formato EXACTO que define la Orden HAC/1177/2024 para
 * eventos del SIF NO es identico al formato de facturas (los campos
 * canonicos cambian). Este formato es funcional, reproducible y
 * suficiente para la cadena local; lo ajustaremos al formato AEAT
 * cuando llegue VF3 (envio real) — antes de esa fecha solo importa
 * que sea reproducible para verify, lo cual es.
 */
@Service
public class SifEventHashService {

    private static final DateTimeFormatter FECHA_HORA_HUSO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx");

    public String computeHash(String nifEmisor,
                              String eventType,
                              String payload,
                              String previousHash,
                              OffsetDateTime generationTime) {
        if (nifEmisor == null || nifEmisor.isBlank()) {
            throw new IllegalArgumentException("nifEmisor requerido");
        }
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType requerido");
        }
        if (generationTime == null) {
            throw new IllegalArgumentException("generationTime requerido");
        }

        String safePayload = payload == null ? "" : escape(payload);
        String previous = previousHash == null ? "" : previousHash.toUpperCase();

        String chain = String.join("&",
                "IDEmisor=" + nifEmisor.trim().toUpperCase(),
                "TipoEvento=" + eventType.trim(),
                "Payload=" + safePayload,
                "Huella=" + previous,
                "FechaHoraHusoGenRegistro=" + generationTime.format(FECHA_HORA_HUSO)
        );

        return sha256Hex(chain).toUpperCase();
    }

    /**
     * Escape minimo para que un payload que contenga "&" o "=" no rompa
     * el parsing del input canonico. Se aplica antes del hash y NO se
     * deshace al verificar — el hash se calcula sobre el escapado.
     */
    private String escape(String raw) {
        return raw
                .replace("&", "\\u0026")
                .replace("=", "\\u003D")
                // Tambien las CR/LF para que la lectura desde BD por
                // diferentes drivers no de un input distinto.
                .replace("\r", "")
                .replace("\n", "\\n");
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
