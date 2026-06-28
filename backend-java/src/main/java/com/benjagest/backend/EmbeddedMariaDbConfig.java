package com.benjagest.backend;

import ch.vorburger.exec.ManagedProcessException;
import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * DEPLOY-PKG — MariaDB EMBEBIDA para el instalable autocontenido (decisión
 * Benjamin 2026-06-27). Solo se activa con {@code benjagest.db.embedded=true}
 * (lo pone el lanzador del instalable). En desarrollo NO se crea este bean, así
 * que Spring usa la MariaDB externa del 3307 de application.yml como hasta ahora.
 *
 * <p>Al crear el {@code DataSource}, arranca una MariaDB embebida (MariaDB4j) con
 * su data dir persistente en {@code ~/.benjagest/mariadb-data}, crea la BD y
 * apunta Hikari ahí. Flyway corre sobre este DataSource (migra V1→Vn en el primer
 * arranque). Se para con la app.
 */
@Configuration
@ConditionalOnProperty(name = "benjagest.db.embedded", havingValue = "true")
public class EmbeddedMariaDbConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedMariaDbConfig.class);
    private static final int PORT = 13307;

    private DB db;

    @Bean
    public DataSource dataSource() throws ManagedProcessException {
        Path dataDir = Paths.get(System.getProperty("user.home"), ".benjagest", "mariadb-data");
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(PORT);
        cfg.setDataDir(dataDir.toString());
        db = DB.newEmbeddedDB(cfg.build());
        db.start();
        db.createDB("benjagest");
        String url = cfg.getURL("benjagest")
                + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
        log.info("DEPLOY-PKG: MariaDB embebida arrancada -> {} (data: {})", url, dataDir);

        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername("root");
        ds.setPassword("");
        ds.setDriverClassName("org.mariadb.jdbc.Driver");
        ds.setConnectionTestQuery("SELECT 1");
        ds.setInitializationFailTimeout(0);
        return ds;
    }

    @PreDestroy
    public void stop() {
        if (db != null) {
            try {
                db.stop();
            } catch (ManagedProcessException ex) {
                log.warn("DEPLOY-PKG: no se pudo parar la MariaDB embebida: {}", ex.getMessage());
            }
        }
    }
}
