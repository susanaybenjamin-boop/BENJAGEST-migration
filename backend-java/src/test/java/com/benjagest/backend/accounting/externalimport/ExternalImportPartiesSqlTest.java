package com.benjagest.backend.accounting.externalimport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.benjagest.backend.accounting.ManualJournalEntryService;
import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.tenant.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Guardia de regresion contra el bug de columnas inexistentes en la
 * importacion de clientes/proveedores (encontrado durante IMP-H).
 *
 * <p>Los INSERT de {@code CUSTOMERS}/{@code SUPPLIERS} usaban nombres de
 * columna que NO existen en el schema vigente ({@code nif}, {@code name},
 * {@code address}), lo que hacia fallar en silencio toda importacion por
 * CSV o JSON. Este test mockea el {@link JdbcTemplate}, captura el SQL que
 * emite el servicio y fija la lista EXACTA de columnas contra el schema
 * real (V1/V2/V85): {@code customers.tax_identifier/legal_name/email/phone/
 * address} y {@code suppliers.tax_identifier/legal_name/email/phone/
 * address_line}. Si alguien vuelve a introducir una columna fantasma, este
 * test lo caza sin necesidad de una BD.
 */
class ExternalImportPartiesSqlTest {

    private JdbcTemplate jdbcTemplate;
    private ExternalImportService service;
    private final List<String> capturedSql = new ArrayList<>();

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        TenantContext tenantContext = mock(TenantContext.class);
        CurrentUserService currentUserService = mock(CurrentUserService.class);
        ManualJournalEntryService manualEntries = mock(ManualJournalEntryService.class);

        lenient().when(tenantContext.getCurrentCompanyId()).thenReturn("company-1");
        // Cada update() registra su SQL y devuelve 1 (fila "insertada").
        lenient().when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenAnswer(inv -> {
                    capturedSql.add(inv.getArgument(0, String.class));
                    return 1;
                });

        service = new ExternalImportService(jdbcTemplate, tenantContext,
                currentUserService, manualEntries, new ObjectMapper());
    }

    /** SQL del INSERT hacia la tabla indicada (customers/suppliers). */
    private String partySql(String table) {
        return capturedSql.stream()
                .filter(s -> s.contains("INTO " + table + " "))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No se emitio INSERT hacia " + table + ". SQL vistos: " + capturedSql));
    }

    @Test
    void csvCustomersUsesRealColumns() {
        service.importData(new ExternalImportService.ImportRequest(
                "CSV", "CUSTOMERS", "clientes.csv",
                "nif;name;email;phone;address\n12345678Z;ACME SL;a@b.com;600;C/ Mayor 1",
                null));
        assertCustomersColumns(partySql("customers"));
    }

    @Test
    void csvSuppliersUsesRealColumns() {
        service.importData(new ExternalImportService.ImportRequest(
                "CSV", "SUPPLIERS", "proveedores.csv",
                "nif;name;email;phone;address\nB12345678;PROVE SL;p@b.com;700;C/ Baja 2",
                null));
        assertSuppliersColumns(partySql("suppliers"));
    }

    @Test
    void jsonCustomersUsesRealColumns() {
        service.importData(new ExternalImportService.ImportRequest(
                "JSON_BENJAGEST", "CUSTOMERS", "export.json",
                "{\"customers\":[{\"nif\":\"12345678Z\",\"name\":\"ACME SL\","
                        + "\"email\":\"a@b.com\",\"phone\":\"600\",\"address\":\"C/ Mayor 1\"}]}",
                null));
        assertCustomersColumns(partySql("customers"));
    }

    @Test
    void jsonSuppliersUsesRealColumns() {
        service.importData(new ExternalImportService.ImportRequest(
                "JSON_BENJAGEST", "SUPPLIERS", "export.json",
                "{\"suppliers\":[{\"nif\":\"B12345678\",\"name\":\"PROVE SL\","
                        + "\"email\":\"p@b.com\",\"phone\":\"700\",\"address\":\"C/ Baja 2\"}]}",
                null));
        assertSuppliersColumns(partySql("suppliers"));
    }

    // Columnas reales de customers: V1 (tax_identifier, legal_name) + V85 (email, phone, address).
    private static void assertCustomersColumns(String sql) {
        assertTrue(sql.contains(
                "id, company_id, tax_identifier, legal_name, email, phone, address, active"),
                "customers debe insertar en columnas reales: " + sql);
        assertFalse(sql.contains("nif"), "customers no debe referenciar la columna fantasma 'nif': " + sql);
    }

    // Columnas reales de suppliers: V2 (tax_identifier, legal_name, address_line, email, phone).
    private static void assertSuppliersColumns(String sql) {
        assertTrue(sql.contains(
                "id, company_id, tax_identifier, legal_name, email, phone, address_line, active"),
                "suppliers debe insertar en columnas reales: " + sql);
        assertFalse(sql.contains("nif"), "suppliers no debe referenciar la columna fantasma 'nif': " + sql);
    }
}
