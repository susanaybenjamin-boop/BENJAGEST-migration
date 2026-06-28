package com.benjagest.backend;

import ch.vorburger.exec.ManagedProcessException;
import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DEPLOY-PKG — MariaDB4j embebida con DATA DIR PERSISTENTE entre arranques.
 *
 * <p>Problema que resuelve: {@code DB.newEmbeddedDB(config)} ejecuta SIEMPRE
 * {@code mariadb-install-db.exe} sobre el data dir. En MariaDB 11.4 ese binario
 * RECHAZA un directorio que no esté vacío ("Data directory ... is not empty.
 * Only new or empty existing directories are accepted"). Resultado: el
 * instalable arrancaría bien la primera vez (dir vacío) y MORIRÍA en el segundo
 * arranque (dir con datos). Inaceptable para un producto instalable.
 *
 * <p>Esta subclase replica fielmente {@code newEmbeddedDB} (construir +
 * prepareDirectories + unpackEmbeddedDb + install) pero {@link #install()} salta
 * la instalación cuando el data dir ya está inicializado (existe la carpeta
 * {@code mysql} del diccionario de sistema). Así el primer arranque instala y los
 * siguientes reutilizan los datos persistidos.
 */
final class PersistentEmbeddedDB extends DB {

    private static final Logger log = LoggerFactory.getLogger(PersistentEmbeddedDB.class);

    private PersistentEmbeddedDB(DBConfiguration config) {
        super(config);
    }

    /** Equivalente a {@code DB.newEmbeddedDB(config)} pero con install idempotente. */
    static PersistentEmbeddedDB create(DBConfiguration config) throws ManagedProcessException {
        PersistentEmbeddedDB db = new PersistentEmbeddedDB(config);
        db.prepareDirectories();
        db.unpackEmbeddedDb();
        db.install();
        return db;
    }

    @Override
    protected synchronized void install() throws ManagedProcessException {
        Path mysqlSystemDir = Paths.get(configuration.getDataDir(), "mysql");
        if (Files.isDirectory(mysqlSystemDir)) {
            log.info("DEPLOY-PKG: data dir ya inicializado ({}), salto mariadb-install-db",
                    configuration.getDataDir());
            return;
        }
        log.info("DEPLOY-PKG: data dir vacío, ejecutando mariadb-install-db (primer arranque)");
        super.install();
    }
}
