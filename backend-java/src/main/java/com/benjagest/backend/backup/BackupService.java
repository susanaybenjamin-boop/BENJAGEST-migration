package com.benjagest.backend.backup;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * BACKUP-LOCAL — backup semanal de la BD y los PDFs de facturas.
 *
 * <p>Decisiones Benjamin 2026-06-12: cron lunes 03:00, ruta default
 * {@code {userHome}/BENJAGEST-backup/}, incluye dump SQL + carpeta
 * {@code benjagest-facturas}, rotación de 12 archivos (3 meses
 * semanales).
 *
 * <p>El dump SQL se genera vía JDBC (no requiere {@code mysqldump} en
 * PATH): SHOW TABLES + SHOW CREATE TABLE + SELECT * → INSERT statements.
 * Para BD pequeña-media (BENJAGEST típica) tarda segundos.
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int ROTATION_KEEP = 12;

    private final DataSource dataSource;
    private final Path backupDir;
    private final Path facturasDir;

    public BackupService(DataSource dataSource,
                          @Value("${benjagest.backup.dir:}") String backupDirCfg,
                          @Value("${benjagest.invoices.storage-root:}") String facturasCfg) {
        this.dataSource = dataSource;
        this.backupDir = Paths.get(
                backupDirCfg == null || backupDirCfg.isBlank()
                        ? com.benjagest.backend.config.BenjagestHome.resolve("backup").toString()
                        : backupDirCfg);
        this.facturasDir = Paths.get(
                facturasCfg == null || facturasCfg.isBlank()
                        ? com.benjagest.backend.config.BenjagestHome.resolve("facturas").toString()
                        : facturasCfg);
    }

    /** Lunes 03:00 (cron Spring: sec min hora dia-mes mes dia-semana). */
    @Scheduled(cron = "0 0 3 * * MON")
    public void runScheduled() {
        try { run(); }
        catch (Exception ex) {
            log.warn("BACKUP-LOCAL: fallo en backup semanal", ex);
        }
    }

    /** Disparable manualmente desde UI. Devuelve la ruta del zip generado. */
    public Path run() throws IOException {
        Files.createDirectories(backupDir);
        String stamp = LocalDate.now().format(STAMP);
        Path zip = backupDir.resolve(stamp + ".zip");
        // Si ya existe del mismo día (re-ejecución), sufijo con índice.
        int suffix = 1;
        while (Files.exists(zip)) {
            zip = backupDir.resolve(stamp + "-" + suffix + ".zip");
            suffix++;
        }
        log.info("BACKUP-LOCAL: generando {}", zip);
        try (OutputStream fos = Files.newOutputStream(zip);
             ZipOutputStream zos = new ZipOutputStream(fos)) {
            // 1) Dump SQL
            zos.putNextEntry(new ZipEntry("benjagest-" + stamp + ".sql"));
            try (BufferedWriter w = new BufferedWriter(
                    new OutputStreamWriter(zos, StandardCharsets.UTF_8) {
                        @Override public void close() throws IOException { /* no cerrar zip */ }
                    })) {
                writeSqlDump(w);
                w.flush();
            }
            zos.closeEntry();
            // 2) Carpeta facturas si existe
            if (Files.isDirectory(facturasDir)) {
                addDirectoryToZip(facturasDir, "facturas/", zos);
            }
        }
        rotate();
        log.info("BACKUP-LOCAL: backup completado {}", zip);
        return zip;
    }

    /**
     * Borra TODOS los ficheros en disco de una empresa ({root}/{companyId}).
     * Primitivo reutilizable para cuando se elimine/purgue una empresa: así
     * sus PDFs/logos no quedan huérfanos (causa de basura en los backups).
     * Seguridad: solo acepta un UUID y verifica que la ruta resuelta cae
     * DENTRO de la carpeta de ficheros (no traversal con ..).
     */
    public void deleteCompanyFiles(String companyId) throws IOException {
        if (companyId == null || !companyId.matches("[0-9a-fA-F-]{36}")) return;
        Path dir = facturasDir.resolve(companyId).normalize();
        if (!dir.startsWith(facturasDir.normalize())) return;
        if (Files.isDirectory(dir)) {
            deleteRecursively(dir);
            log.info("BACKUP-LOCAL: borrados ficheros de empresa {}", companyId);
        }
    }

    /**
     * Purga las carpetas de empresa en disco cuyo id ya NO existe en la BD
     * (huérfanas, p. ej. empresas de prueba purgadas). Seguro: nunca toca una
     * empresa que siga en la tabla {@code companies}. Devuelve cuántas borró.
     */
    public int purgeOrphanedCompanyFiles() throws IOException {
        if (!Files.isDirectory(facturasDir)) return 0;
        java.util.Set<String> alive = new java.util.HashSet<>();
        try (var conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id FROM companies")) {
            while (rs.next()) alive.add(rs.getString(1));
        } catch (java.sql.SQLException ex) {
            throw new IOException("No se pudieron leer las empresas vivas", ex);
        }
        int deleted = 0;
        try (var stream = Files.list(facturasDir)) {
            for (Path dir : (Iterable<Path>) stream::iterator) {
                if (!Files.isDirectory(dir)) continue;
                String name = dir.getFileName().toString();
                if (!alive.contains(name)) {
                    deleteRecursively(dir);
                    deleted++;
                    log.info("BACKUP-LOCAL: purgada carpeta huerfana {}", name);
                }
            }
        }
        return deleted;
    }

    private void deleteRecursively(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); }
                        catch (IOException ex) { log.warn("BACKUP-LOCAL: no se pudo borrar {}", p); }
                    });
        }
    }

    /** Lista los backups existentes ordenados de más reciente a más antiguo. */
    public List<BackupInfo> list() throws IOException {
        if (!Files.isDirectory(backupDir)) return List.of();
        try (var stream = Files.list(backupDir)) {
            return stream.filter(p -> p.toString().endsWith(".zip"))
                    .sorted(Comparator.comparing((Path p) -> {
                        try { return Files.getLastModifiedTime(p); }
                        catch (IOException ex) { return null; }
                    }).reversed())
                    .map(p -> {
                        try {
                            return new BackupInfo(
                                    p.getFileName().toString(),
                                    p.toAbsolutePath().toString(),
                                    Files.size(p),
                                    Files.getLastModifiedTime(p).toInstant().toString());
                        } catch (IOException ex) {
                            return new BackupInfo(p.getFileName().toString(),
                                    p.toAbsolutePath().toString(), 0, null);
                        }
                    })
                    .toList();
        }
    }

    private void rotate() throws IOException {
        List<BackupInfo> all = list();
        if (all.size() <= ROTATION_KEEP) return;
        for (int i = ROTATION_KEEP; i < all.size(); i++) {
            try { Files.deleteIfExists(Paths.get(all.get(i).fullPath())); }
            catch (IOException ex) { log.warn("BACKUP-LOCAL: no se pudo borrar {}", all.get(i).filename()); }
        }
    }

    private void writeSqlDump(BufferedWriter w) throws IOException {
        w.write("-- BENJAGEST backup " + LocalDate.now() + "\n");
        w.write("-- Generado por BackupService (JDBC dump)\n");
        w.write("SET FOREIGN_KEY_CHECKS = 0;\n\n");
        try (var conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            List<String> tables = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("SHOW TABLES")) {
                while (rs.next()) tables.add(rs.getString(1));
            }
            for (String table : tables) {
                if ("flyway_schema_history".equalsIgnoreCase(table)) continue;
                w.write("-- ----- " + table + " -----\n");
                w.write("DROP TABLE IF EXISTS `" + table + "`;\n");
                try (Statement st2 = conn.createStatement();
                     ResultSet rs = st2.executeQuery("SHOW CREATE TABLE `" + table + "`")) {
                    if (rs.next()) {
                        w.write(rs.getString(2));
                        w.write(";\n\n");
                    }
                }
                dumpTableData(conn, table, w);
                w.write("\n");
            }
            w.write("SET FOREIGN_KEY_CHECKS = 1;\n");
        } catch (java.sql.SQLException ex) {
            throw new IOException("Error generando dump SQL", ex);
        }
    }

    private void dumpTableData(java.sql.Connection conn, String table, BufferedWriter w)
            throws java.sql.SQLException, IOException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM `" + table + "`")) {
            ResultSetMetaData meta = rs.getMetaData();
            int cols = meta.getColumnCount();
            StringBuilder colList = new StringBuilder();
            for (int i = 1; i <= cols; i++) {
                if (i > 1) colList.append(", ");
                colList.append("`").append(meta.getColumnName(i)).append("`");
            }
            boolean first = true;
            int batched = 0;
            StringBuilder buf = new StringBuilder();
            while (rs.next()) {
                if (first) {
                    buf.append("INSERT INTO `").append(table).append("` (")
                            .append(colList).append(") VALUES\n");
                    first = false;
                } else {
                    buf.append(",\n");
                }
                buf.append("(");
                for (int i = 1; i <= cols; i++) {
                    if (i > 1) buf.append(", ");
                    Object v = rs.getObject(i);
                    buf.append(sqlLiteral(v));
                }
                buf.append(")");
                batched++;
                if (batched >= 200) {
                    buf.append(";\n");
                    w.write(buf.toString());
                    buf.setLength(0);
                    first = true;
                    batched = 0;
                }
            }
            if (batched > 0) {
                buf.append(";\n");
                w.write(buf.toString());
            }
        }
    }

    private static String sqlLiteral(Object v) {
        if (v == null) return "NULL";
        if (v instanceof Number n) return n.toString();
        if (v instanceof Boolean b) return b ? "1" : "0";
        if (v instanceof byte[] b) {
            StringBuilder hex = new StringBuilder("0x");
            for (byte by : b) hex.append(String.format("%02x", by));
            return hex.toString();
        }
        String s = v.toString();
        return "'" + s.replace("\\", "\\\\").replace("'", "''")
                .replace("\n", "\\n").replace("\r", "\\r") + "'";
    }

    private void addDirectoryToZip(Path root, String prefix, ZipOutputStream zos) throws IOException {
        try (var walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (Files.isDirectory(p)) continue;
                String entry = prefix + root.relativize(p).toString().replace('\\', '/');
                zos.putNextEntry(new ZipEntry(entry));
                Files.copy(p, zos);
                zos.closeEntry();
            }
        }
    }

    public record BackupInfo(String filename, String fullPath, long sizeBytes, String lastModified) {}
}
