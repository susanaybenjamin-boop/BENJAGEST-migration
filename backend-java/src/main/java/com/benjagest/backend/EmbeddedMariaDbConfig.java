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
        // DB-LOCK (RD 1007/2023, inalterabilidad): la BD SOLO escucha en
        // localhost. Antes escuchaba en 0.0.0.0 y cualquier equipo de la LAN
        // podía conectarse.
        cfg.addArg("--bind-address=127.0.0.1");
        cfg.addArg("--skip-name-resolve");
        // Auto-sanación: si un cierre anterior dejó un mariadbd huérfano vivo,
        // retiene el lock del data dir ("ibdata1 must be writable") y este
        // arranque fallaría. Lo cerramos antes de empezar.
        killStaleEmbeddedMariadb();
        // PersistentEmbeddedDB en vez de DB.newEmbeddedDB: salta mariadb-install-db
        // si el data dir ya está inicializado (MariaDB 11.4 lo rechaza si no está
        // vacío). Sin esto el instalable moriría en el 2º arranque.
        db = PersistentEmbeddedDB.create(cfg.build());
        db.start();

        // DB-LOCK: credencial aleatoria POR INSTALACIÓN. En el primer arranque
        // (o en una instalación previa aún abierta) se fija una contraseña root
        // aleatoria, se eliminan los usuarios anónimos (permitían entrar con
        // CUALQUIER usuario/contraseña, p.ej. benjagest/benjagest) y se guarda
        // la clave en ~/.benjagest/.dbkey (oculto + ACL solo del usuario).
        // Nadie puede conectarse a la BD por fuera de la app sin esa clave.
        String password = ensureDbLocked(cfg);

        String url = cfg.getURL("benjagest")
                + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
        log.info("DEPLOY-PKG: MariaDB embebida arrancada -> {} (data: {})", url, dataDir);

        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername("root");
        ds.setPassword(password);
        ds.setDriverClassName("org.mariadb.jdbc.Driver");
        ds.setConnectionTestQuery("SELECT 1");
        ds.setInitializationFailTimeout(0);
        return ds;
    }

    /**
     * DB-LOCK — devuelve la contraseña root de esta instalación, blindando la
     * BD si aún está abierta.
     *
     * <p>Flujo: si existe {@code ~/.benjagest/.dbkey}, la BD ya está blindada y
     * se usa esa clave. Si no existe, la BD está en estado "de fábrica" (root
     * sin contraseña + usuarios anónimos): se crea la BD si falta, se genera
     * una clave aleatoria de 40 caracteres, se eliminan los usuarios anónimos
     * y la BD test, se fija la clave a todos los root y se persiste en el
     * fichero (oculto, ACL restringida al usuario de Windows).
     *
     * <p>Si el fichero se pierde con la BD ya blindada, el arranque falla con
     * un mensaje claro (la alternativa sería una puerta trasera, que es
     * exactamente lo que este slice elimina).
     */
    private String ensureDbLocked(DBConfigurationBuilder cfg) {
        Path keyFile = Paths.get(System.getProperty("user.home"), ".benjagest", ".dbkey");
        try {
            // 1) Clave de esta instalación: la existente, o una nueva que se
            //    persiste ANTES de aplicar el blindaje (si la app muriera a
            //    mitad, el próximo arranque encuentra la clave y re-aplica).
            String pwd;
            if (java.nio.file.Files.exists(keyFile)) {
                pwd = java.nio.file.Files.readString(keyFile).trim();
            } else {
                pwd = randomKey(40);
                java.nio.file.Files.writeString(keyFile, pwd);
                restrictKeyFile(keyFile);
            }

            // 2) ¿Sigue abierta la puerta de fábrica (root sin contraseña)?
            //    Cubre el primer arranque, una instalación previa sin blindar,
            //    y un blindaje interrumpido. Idempotente.
            if (canConnect("")) {
                harden(pwd);
                log.info("DB-LOCK: BD embebida blindada (root con clave aleatoria por instalación, "
                        + "usuarios anónimos eliminados; clave en {})", keyFile);
            } else if (!canConnect(pwd)) {
                throw new IllegalStateException(
                        "DB-LOCK: la BD está blindada pero la clave de " + keyFile
                        + " no abre. Si el fichero se restauró de otra instalación o se editó, "
                        + "recupera el original desde la copia de seguridad; sin él no hay "
                        + "acceso (y esa es la garantía de inalterabilidad).");
            }

            // 3) Asegurar que la BD de la app existe (con la credencial ya válida).
            try (java.sql.Connection c = connect("mysql", pwd);
                 java.sql.Statement st = c.createStatement()) {
                st.executeUpdate("CREATE DATABASE IF NOT EXISTS benjagest");
            }
            return pwd;
        } catch (java.sql.SQLException ex) {
            throw new IllegalStateException("DB-LOCK: no se pudo abrir la BD embebida blindada", ex);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("DB-LOCK: no se pudo leer/escribir " + keyFile, ex);
        }
    }

    /** Aplica el blindaje con la conexión de fábrica (root sin contraseña). */
    private void harden(String pwd) throws java.sql.SQLException {
        try (java.sql.Connection c = connect("mysql", "");
             java.sql.Statement st = c.createStatement()) {
            // 1) Fuera usuarios anónimos (''@cualquier-host): con ellos, CUALQUIER
            //    usuario inventado (p.ej. benjagest/benjagest) podía conectarse.
            st.executeUpdate("DELETE FROM mysql.global_priv WHERE User = ''");
            // 2) Fuera la BD de pruebas.
            st.executeUpdate("DROP DATABASE IF EXISTS test");
            // 3) root solo desde local.
            st.executeUpdate("DROP USER IF EXISTS 'root'@'%'");
            // 4) Contraseña aleatoria para todos los root locales que existan.
            st.executeUpdate("ALTER USER IF EXISTS 'root'@'localhost' IDENTIFIED BY '" + pwd + "'");
            st.executeUpdate("ALTER USER IF EXISTS 'root'@'127.0.0.1' IDENTIFIED BY '" + pwd + "'");
            st.executeUpdate("ALTER USER IF EXISTS 'root'@'::1' IDENTIFIED BY '" + pwd + "'");
            st.executeUpdate("FLUSH PRIVILEGES");
        }
    }

    private java.sql.Connection connect(String database, String pwd) throws java.sql.SQLException {
        return java.sql.DriverManager.getConnection(
                "jdbc:mariadb://127.0.0.1:" + PORT + "/" + database, "root", pwd);
    }

    private boolean canConnect(String pwd) {
        try (java.sql.Connection ignored = connect("mysql", pwd)) {
            return true;
        } catch (java.sql.SQLException ex) {
            return false;
        }
    }

    /** Clave alfanumérica criptográficamente aleatoria (sin caracteres a escapar). */
    private static String randomKey(int len) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        java.security.SecureRandom rnd = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        return sb.toString();
    }

    /** Oculta el fichero de la clave y restringe su ACL al usuario actual (best-effort). */
    private void restrictKeyFile(Path keyFile) {
        String user = System.getProperty("user.name");
        try {
            new ProcessBuilder("icacls", keyFile.toString(), "/inheritance:r",
                    "/grant:r", user + ":F").start().waitFor();
            new ProcessBuilder("attrib", "+h", keyFile.toString()).start().waitFor();
        } catch (Exception ex) {
            log.warn("DB-LOCK: no se pudo restringir la ACL de {} ({}). La clave sigue "
                    + "siendo aleatoria por instalación.", keyFile, ex.getMessage());
        }
    }

    /**
     * Cierra cualquier {@code mariadbd} embebido huérfano que quedara de un
     * arranque anterior reteniendo el lock de NUESTRO data dir. En Windows
     * {@code Process.destroy()} es forzoso, así que el {@link #stop()} que para
     * MariaDB puede no ejecutarse al cerrar la app y dejar el proceso vivo.
     *
     * <p>Discrimina por la RUTA del ejecutable ({@code ProcessHandle.command()},
     * que SÍ está disponible en Windows, al contrario que {@code commandLine()}
     * que viene vacío): nuestro mariadbd embebido corre desde el base dir de
     * MariaDB4j ({@code ...\Temp\MariaDB4j\base\bin\mariadbd.exe}), ruta que NO
     * comparte la MariaDB del proyecto (3307) ni ninguna otra instalación.
     */
    private void killStaleEmbeddedMariadb() {
        ProcessHandle.allProcesses()
                .filter(ph -> ph.info().command()
                        .map(c -> {
                            String lc = c.toLowerCase();
                            return (lc.endsWith("mariadbd.exe") || lc.endsWith("mariadbd"))
                                    && lc.contains("mariadb4j");
                        })
                        .orElse(false))
                .forEach(ph -> {
                    log.warn("DEPLOY-PKG: mariadbd embebido huérfano (PID {}) reteniendo el data dir; "
                            + "lo cierro antes de arrancar.", ph.pid());
                    ph.destroyForcibly();
                    try {
                        ph.onExit().get(10, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (Exception ignored) {
                        // si no confirma la salida, seguimos: el start hará el diagnóstico real
                    }
                });
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
