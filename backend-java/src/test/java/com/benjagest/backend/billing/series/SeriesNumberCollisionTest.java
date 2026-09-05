package com.benjagest.backend.billing.series;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.Date;
import java.util.Optional;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * NUM-1 — La serie de facturación se quedaba ATASCADA cuando una factura
 * histórica importada ya ocupaba el número que al contador le tocaba emitir.
 *
 * <p>Caso real de Benjamin (2026-09-05, datos de su BD): serie {@code FRA} con
 * {@code next_number = 8} y {@code FRA-2026-0008} ya ocupado por una histórica
 * importada por PDF. Al validar una factura nueva:
 *
 * <ol>
 *   <li>{@code claimNextNumber} emitía el 8 y subía el contador a 9;</li>
 *   <li>el UPDATE de {@code markValidated} chocaba con la única
 *       {@code (company_id, invoice_number)} y lanzaba un 500 con error SQL;</li>
 *   <li>la transacción hacía rollback y <b>el contador volvía a 8</b>.</li>
 * </ol>
 *
 * <p>Es decir: el reintento pedía el MISMO número y fallaba igual, para siempre.
 * No se podía emitir ninguna factura más en esa serie.
 *
 * <p>Estos tests corren contra MariaDB de verdad con todas las migraciones,
 * porque lo que se está probando es precisamente el choque con una restricción
 * de la base de datos.
 */
class SeriesNumberCollisionTest {

    private static final String COMPANY = "c0num100-0000-0000-0000-000000000001";

    private static DB db;
    private static JdbcTemplate jdbc;
    private static SeriesRepository repository;
    private static SeriesService service;

    private static final class FixedTenant implements TenantContext {
        @Override public String getCurrentCompanyId() { return COMPANY; }
        @Override public void setCurrentCompanyId(String id) { /* fijo en el test */ }
    }

    @BeforeAll
    static void boot() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        db = DB.newEmbeddedDB(cfg.build());
        db.start();
        db.createDB("benjagestnumtest");

        DriverManagerDataSource ds =
                new DriverManagerDataSource(cfg.getURL("benjagestnumtest"), "root", "");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration")
                .outOfOrder(true).load().migrate();
        jdbc = new JdbcTemplate(ds);

        // El repositorio va MOCKEADO (el FOR UPDATE y el UPDATE del contador no
        // son lo que se prueba aqui); el JdbcTemplate es REAL, porque la
        // comprobacion de numero ocupado consulta sales_invoices de verdad.
        repository = Mockito.mock(SeriesRepository.class);
        service = new SeriesService(repository, new FixedTenant(),
                Mockito.mock(com.benjagest.backend.auth.CurrentUserService.class), jdbc);

        jdbc.update("INSERT INTO companies (id, legal_name, company_type) "
                + "VALUES (?, 'Asesoria Benjamin', 'INTERNAL')", COMPANY);
        String customerId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO customers (id, company_id, legal_name, customer_type, active)
                VALUES (?, ?, 'Cliente historico', 'COMPANY', TRUE)
                """, customerId, COMPANY);

        // Las 8 historicas importadas, tal cual estan en la BD de Benjamin.
        for (int i = 1; i <= 8; i++) {
            historica(customerId, String.format("FRA-2026-%04d", i));
        }
    }

    @AfterAll
    static void stop() throws Exception {
        if (db != null) {
            db.stop();
        }
    }

    private static void historica(String customerId, String number) {
        jdbc.update("""
                INSERT INTO sales_invoices (id, company_id, customer_id, invoice_number,
                        invoice_date, invoice_type, status, payment_status,
                        subtotal, vat_total, retention_total, total, paid_amount, currency)
                VALUES (?, ?, ?, ?, ?, 'HISTORICAL', 'VALIDATED', 'PENDING',
                        100.00, 21.00, 0, ?, 0, 'EUR')
                """, UUID.randomUUID().toString(), COMPANY, customerId, number,
                Date.valueOf("2026-05-10"), new BigDecimal("121.00"));
    }

    /** Serie FRA en el estado exacto en el que estaba atascada: next = 8. */
    private static Series serieFraEn(int nextNumber) {
        return new Series(UUID.randomUUID().toString(), COMPANY, null,
                "FRA", "SALES", "BY_YEAR", "{CODE}-{YYYY}-{0000}",
                nextNumber, 2026, false, true, null, null);
    }

    @Test
    void saltaElNumeroQueYaOcupaUnaHistorica() {
        Series serie = serieFraEn(8);
        Mockito.when(repository.findByIdForUpdate(serie.id())).thenReturn(Optional.of(serie));

        SeriesService.ClaimedNumber claimed = service.claimNextNumber(serie.id());

        assertEquals("FRA-2026-0009", claimed.formatted(),
                "el 0008 lo tiene una historica; hay que emitir el 0009, no chocar");
        assertEquals(9, claimed.sequence());
    }

    @Test
    void elContadorQuedaApuntandoAlSiguienteLibre() {
        Series serie = serieFraEn(8);
        Mockito.when(repository.findByIdForUpdate(serie.id())).thenReturn(Optional.of(serie));

        service.claimNextNumber(serie.id());

        ArgumentCaptor<Integer> next = ArgumentCaptor.forClass(Integer.class);
        Mockito.verify(repository).updateCounter(Mockito.eq(serie.id()), next.capture(), Mockito.any());
        assertEquals(10, next.getValue(),
                "tras emitir el 0009 el contador debe quedar en 10, no volver al 8");
    }

    /** Desde el 1, con 8 ocupados seguidos, tiene que saltarlos todos de una vez. */
    @Test
    void saltaUnBloqueEnteroDeNumerosOcupados() {
        Series serie = serieFraEn(1);
        Mockito.when(repository.findByIdForUpdate(serie.id())).thenReturn(Optional.of(serie));

        assertEquals("FRA-2026-0009", service.claimNextNumber(serie.id()).formatted(),
                "del 0001 al 0008 estan todos ocupados por historicas");
    }

    /** Sin colision, el comportamiento de siempre: ni salta ni cambia nada. */
    @Test
    void sinColisionEmiteElNumeroQueTocaComoSiempre() {
        Series serie = serieFraEn(20);
        Mockito.when(repository.findByIdForUpdate(serie.id())).thenReturn(Optional.of(serie));

        SeriesService.ClaimedNumber claimed = service.claimNextNumber(serie.id());

        assertEquals("FRA-2026-0020", claimed.formatted());
        assertEquals(20, claimed.sequence());
        Mockito.verify(repository).updateCounter(Mockito.eq(serie.id()), Mockito.eq(21), Mockito.any());
    }

    /** Otra serie de la misma empresa no se contamina con los numeros de FRA. */
    @Test
    void otraSerieDeLaMismaEmpresaNoSeVeAfectada() {
        Series rect = new Series(UUID.randomUUID().toString(), COMPANY, null,
                "RECT", "SALES", "BY_YEAR", "{CODE}-{YYYY}-{0000}",
                1, 2026, false, true, null, null);
        Mockito.when(repository.findByIdForUpdate(rect.id())).thenReturn(Optional.of(rect));

        assertEquals("RECT-2026-0001", service.claimNextNumber(rect.id()).formatted(),
                "el prefijo de la serie hace que el numero formateado sea distinto");
    }

    /**
     * El numero emitido tiene que poder INSERTARSE de verdad. Es la prueba que
     * de verdad importa: que no vuelva a saltar la unica de la BD.
     */
    @Test
    void elNumeroEmitidoEntraEnLaBaseDeDatosSinChocar() {
        Series serie = serieFraEn(8);
        Mockito.when(repository.findByIdForUpdate(serie.id())).thenReturn(Optional.of(serie));
        String numero = service.claimNextNumber(serie.id()).formatted();

        String customerId = jdbc.queryForObject(
                "SELECT id FROM customers WHERE company_id = ? LIMIT 1", String.class, COMPANY);
        int filas = jdbc.update("""
                INSERT INTO sales_invoices (id, company_id, customer_id, invoice_number,
                        invoice_date, invoice_type, status, payment_status,
                        subtotal, vat_total, retention_total, total, paid_amount, currency)
                VALUES (?, ?, ?, ?, ?, 'NORMAL', 'VALIDATED', 'PENDING',
                        100.00, 21.00, 0, 121.00, 0, 'EUR')
                """, UUID.randomUUID().toString(), COMPANY, customerId, numero,
                Date.valueOf("2026-09-05"));

        assertTrue(filas == 1, "la factura nueva debe poder guardarse con ese numero");
        jdbc.update("DELETE FROM sales_invoices WHERE company_id = ? AND invoice_number = ?",
                COMPANY, numero);
    }
}
