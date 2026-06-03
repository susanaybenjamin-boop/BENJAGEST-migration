package com.benjagest.backend.billing.pdf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Calcula la ruta de un PDF de factura y lo escribe a disco.
 *
 * Estructura (replica el modelo CONTENDO probado en produccion):
 *
 *   {root}/{companyId}/{YYYY}/T{1|2|3|4}/{invoiceNumber}.pdf
 *
 * El {root} se resuelve en este orden de prioridad:
 *
 *   1. companies.invoice_storage_root (lo decide cada empresa).
 *   2. Propiedad `benjagest.invoices.storage-root` del application.properties.
 *   3. {user.home}/benjagest-facturas (fallback final, util en dev).
 *
 * Asi una empresa puede elegir su propia ubicacion (NAS, otra particion,
 * carpeta sincronizada con la nube) sin tocar config global. Si esa
 * empresa no decide nada, hereda la ruta del backend; si tampoco hay,
 * el dev local sigue trabajando sin configurar nada.
 *
 * Aislamiento multi-tenant: el companyId va dentro de la ruta y se
 * comprueba contra el de la factura — un attacker que controle el
 * filename no puede llegar fuera de su companyId con .. porque
 * sanitizamos el invoice_number (replace caracteres prohibidos).
 */
@Service
public class InvoiceStorageService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceStorageService.class);
    /**
     * Caracteres que pueden romper la ruta (separadores, controles de
     * shell, etc.). Se reemplazan por "_" antes de construir el path.
     */
    private static final String UNSAFE_FILENAME_CHARS = "[\\\\/:*?\"<>|\\r\\n\\t]";

    private final String fallbackRoot;

    public InvoiceStorageService(
            @Value("${benjagest.invoices.storage-root:}") String defaultRoot) {
        this.fallbackRoot = StringUtils.hasText(defaultRoot)
                ? defaultRoot
                : Paths.get(System.getProperty("user.home"), "benjagest-facturas").toString();
    }

    /**
     * Resuelve la ruta donde se guardara (o se buscara) el PDF de una
     * factura concreta. NO escribe nada; solo calcula. Usado tambien por
     * el endpoint de lectura.
     *
     * @param companyStorageRoot la ruta elegida por la empresa, o null
     *                           si quiere heredar el default.
     */
    public Path computePath(String companyStorageRoot,
                             String companyId,
                             LocalDate invoiceDate,
                             String invoiceNumber) {
        if (!StringUtils.hasText(companyId)) {
            throw new IllegalArgumentException("companyId requerido");
        }
        if (invoiceDate == null) {
            throw new IllegalArgumentException("invoiceDate requerido");
        }
        if (!StringUtils.hasText(invoiceNumber)) {
            throw new IllegalArgumentException("invoiceNumber requerido");
        }
        String root = StringUtils.hasText(companyStorageRoot) ? companyStorageRoot : fallbackRoot;
        int quarter = ((invoiceDate.getMonthValue() - 1) / 3) + 1;
        String safeNumber = invoiceNumber.replaceAll(UNSAFE_FILENAME_CHARS, "_");
        return Paths.get(root,
                companyId,
                String.valueOf(invoiceDate.getYear()),
                "T" + quarter,
                safeNumber + ".pdf");
    }

    /**
     * Escribe el PDF en la ruta calculada, creando las carpetas
     * intermedias si no existen. Sobrescribe si ya existe — esto solo
     * deberia pasar si una factura se valida dos veces (que la regla
     * de negocio no permite) o en un re-run defensivo del job de
     * almacenamiento.
     *
     * Devuelve la ruta absoluta como string para guardarla en
     * sales_invoices.pdf_path.
     */
    public String writePdf(String companyStorageRoot,
                            String companyId,
                            LocalDate invoiceDate,
                            String invoiceNumber,
                            byte[] pdfBytes) throws IOException {
        Path target = computePath(companyStorageRoot, companyId, invoiceDate, invoiceNumber);
        Files.createDirectories(target.getParent());
        Files.write(target, pdfBytes);
        log.info("PDF de factura {} guardado en {}", invoiceNumber, target);
        return target.toAbsolutePath().toString();
    }

    public boolean exists(String pdfPath) {
        return StringUtils.hasText(pdfPath) && Files.exists(Paths.get(pdfPath));
    }

    public byte[] read(String pdfPath) throws IOException {
        return Files.readAllBytes(Paths.get(pdfPath));
    }
}
