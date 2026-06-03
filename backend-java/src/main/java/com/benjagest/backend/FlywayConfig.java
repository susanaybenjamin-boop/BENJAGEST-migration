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
}
