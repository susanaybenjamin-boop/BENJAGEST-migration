package com.benjagest.backend;

import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Estrategia personalizada de Flyway: repair() antes de migrate().
 *
 * Por que: en MariaDB el DDL no es transaccional. Si una migracion
 * falla a mitad (p.ej. una ALTER de dos columnas donde la primera se
 * aplica y la segunda revienta), Flyway deja una fila en
 * flyway_schema_history marcada como FAILED. En arranques siguientes,
 * Flyway hace `validate` antes de `migrate` y rechaza continuar — el
 * mensaje tipico es "Detected failed migration to version X. Please
 * remove any half-completed changes then run repair".
 *
 * En esta app, todas las migraciones criticas son idempotentes (usan
 * IF NOT EXISTS, NOT EXISTS en INSERT, etc), asi que repair() es
 * seguro: elimina las filas FAILED y la siguiente migrate() reaplica
 * la version que se quedo a medias. Si esa version es idempotente,
 * vuelve a quedar OK; si no lo es, fallara y se vera el mismo error
 * (no enmascaramos nada — solo evitamos el bloqueo por una entrada de
 * historial que ya no aplica).
 *
 * Coste: dos llamadas a Flyway en arranque (repair + migrate). En la
 * practica repair() es no-op cuando todo esta limpio; cuando hay algo
 * que limpiar, deja la BD lista en el mismo arranque sin necesidad de
 * comandos manuales (mvn flyway:repair).
 */
@Configuration
public class FlywayConfig {

    @Bean
    public FlywayMigrationStrategy repairThenMigrate() {
        return flyway -> {
            flyway.repair();
            flyway.migrate();
        };
    }

    /**
     * Permite aplicar migraciones "fuera de orden" (out-of-order). Caso
     * real 2026-06-08: V72 y V73 ya estaban aplicadas en BD desde una
     * sesión previa cuando V71 se añadió posteriormente (advisory_collaborations
     * de L4-6). Sin esta config Flyway aborta con
     * "Detected resolved migration not applied to database: 71".
     *
     * <p>Es seguro siempre que las migraciones sean independientes (no
     * tocan los mismos objetos). En esta app cada V toca tablas/columnas
     * distintas; la única posible colisión sería dos V's tocando la
     * misma columna en orden distinto, caso que detectaríamos en code
     * review.
     */
    @Bean
    public org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer outOfOrderCustomizer() {
        return cfg -> cfg.outOfOrder(true);
    }
}
