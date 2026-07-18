package com.benjagest.backend.system;

import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SYS-INFO (2026-07-18) — Info NO sensible del entorno de ejecución para que la
 * UI pueda mostrar a qué base de datos está conectada (PRUEBA en el 3307 vs
 * PRODUCCIÓN embebida en el 13307) y no se opere por error sobre datos reales.
 *
 * <p>Nace de un susto de Benjamin: lanzaba la UI creyendo estar en la BD de
 * prueba y en realidad hablaba con el backend de producción (el instalable, BD
 * embebida en el 13307), porque la UI apunta al 8080 por defecto y ahí estaba el
 * backend equivocado.
 *
 * <p><b>No expone credenciales</b>: solo {@code jdbc:mariadb://host:puerto/bd}
 * (sin la contraseña ni los query params). Requiere sesión (JWT) como cualquier
 * endpoint no público; no lleva {@code @RequiresRole} porque cualquier usuario
 * autenticado puede ver en qué entorno está.
 */
@RestController
@RequestMapping("/api/system")
public class SystemInfoController {

    private final DataSource dataSource;
    private final Environment env;

    public SystemInfoController(DataSource dataSource, Environment env) {
        this.dataSource = dataSource;
        this.env = env;
    }

    @GetMapping("/info")
    public Map<String, Object> info() {
        boolean embedded = "true".equalsIgnoreCase(
                env.getProperty("benjagest.db.embedded", "false"));
        return Map.of(
                "database", sanitize(jdbcUrl()),
                "embedded", embedded);
    }

    /** URL JDBC REAL de la conexión (refleja el 13307 embebido aunque el yml diga 3307). */
    private String jdbcUrl() {
        if (dataSource instanceof HikariDataSource hikari && hikari.getJdbcUrl() != null) {
            return hikari.getJdbcUrl();
        }
        try (var c = dataSource.getConnection()) {
            return c.getMetaData().getURL();
        } catch (Exception ex) {
            return env.getProperty("spring.datasource.url", "");
        }
    }

    /** Quita credenciales y query params: deja jdbc:mariadb://host:puerto/bd. */
    private static String sanitize(String url) {
        if (url == null) {
            return "";
        }
        int q = url.indexOf('?');
        return q >= 0 ? url.substring(0, q) : url;
    }
}
