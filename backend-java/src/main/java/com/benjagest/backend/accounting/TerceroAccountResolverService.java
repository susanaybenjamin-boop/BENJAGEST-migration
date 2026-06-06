package com.benjagest.backend.accounting;

import com.benjagest.backend.tenant.TenantContext;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resuelve la sub-cuenta PGC de un tercero (cliente o proveedor).
 *
 * <p>Port de la función {@code getOrCreateCuentaTercero} de CONTENDO
 * ({@code backend/src/services/contabilidadService.js} línea 1409). Sigue el
 * mismo algoritmo:
 * <ol>
 *   <li>Búsqueda por {@code tercero_ref} (nombre normalizado UPPER+TRIM).</li>
 *   <li>Búsqueda por {@code tercero_nif} si tiene NIF (fallback si el
 *       proveedor cambió de razón social).</li>
 *   <li>Búsqueda por nombre parcial (compatibilidad con datos legacy).</li>
 *   <li>Si no existe: crea con código secuencial {@code prefijo + nextNum}
 *       padded a 3 dígitos (4000001, 4000002… / 4300001, 4300002…).</li>
 * </ol>
 *
 * <p>Si el caller no aporta nombre ni NIF, devuelve la cuenta padre genérica
 * (400 ó 430) — el asiento se podrá validar igualmente pero no se distinguirá
 * por tercero. Esto replica el comportamiento defensivo de CONTENDO cuando el
 * proveedor es "Proveedor" o el tercero no está identificado.
 *
 * <p>Decisión de scope: por ahora <b>no</b> implementamos {@code tercero_aliases}
 * (la columna existe en V55 pero queda para el slice de "fusionar terceros"
 * cuando el asesor detecte que dos sub-cuentas eran realmente la misma).
 */
@Service
public class TerceroAccountResolverService {

    public enum TerceroType {
        CLIENTE("4300", "430", "ASSET",  "Clientes",     4, 43),
        PROVEEDOR("4000", "400", "LIABILITY", "Proveedores", 4, 40);

        final String prefix;
        final String parentCode;
        final String accountType;
        final String parentName;
        final int group;
        final int subgroup;

        TerceroType(String prefix, String parentCode, String accountType,
                    String parentName, int group, int subgroup) {
            this.prefix = prefix;
            this.parentCode = parentCode;
            this.accountType = accountType;
            this.parentName = parentName;
            this.group = group;
            this.subgroup = subgroup;
        }
    }

    public record ResolvedAccount(String accountId, String code, String name) {}

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;

    public TerceroAccountResolverService(JdbcTemplate jdbcTemplate,
                                          TenantContext tenantContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
    }

    /** Atajo para proveedores. */
    @Transactional
    public ResolvedAccount getOrCreateForSupplier(String nif, String name) {
        return getOrCreate(TerceroType.PROVEEDOR, nif, name);
    }

    /** Atajo para clientes. */
    @Transactional
    public ResolvedAccount getOrCreateForCustomer(String nif, String name) {
        return getOrCreate(TerceroType.CLIENTE, nif, name);
    }

    /**
     * Núcleo del resolver. Devuelve sub-cuenta existente o la crea.
     * Nunca devuelve null: si el tercero no está identificado, devuelve
     * la cuenta padre genérica si existe, y como último recurso un
     * ResolvedAccount con accountId null (el caller decide).
     */
    @Transactional
    public ResolvedAccount getOrCreate(TerceroType type, String nif, String name) {
        String companyId = tenantContext.getCurrentCompanyId();
        String nameNorm = normalize(name);
        String nifNorm = normalizeNif(nif);

        // 0. Tercero no identificado → devolver cuenta padre genérica.
        if (nameNorm.isEmpty() && nifNorm.isEmpty()) {
            return findGenericParent(companyId, type);
        }

        // 1. Por tercero_ref (nombre normalizado).
        if (!nameNorm.isEmpty()) {
            ResolvedAccount byRef = findByTerceroRef(companyId, type, nameNorm);
            if (byRef != null) return byRef;
        }

        // 2. Por tercero_nif (más estable que el nombre).
        if (!nifNorm.isEmpty()) {
            ResolvedAccount byNif = findByTerceroNif(companyId, type, nifNorm);
            if (byNif != null) {
                // Si el nombre actual no coincide con tercero_ref, podríamos
                // añadirlo a tercero_aliases. Lo dejamos para el slice de
                // "fusionar terceros" — por ahora con encontrarla basta.
                return byNif;
            }
        }

        // 3. Por nombre parcial (fallback legacy).
        if (!nameNorm.isEmpty() && nameNorm.length() >= 4) {
            ResolvedAccount byName = findByNameLike(companyId, type, nameNorm);
            if (byName != null) return byName;
        }

        // 4. Crear nueva sub-cuenta.
        return createSubaccount(companyId, type, nifNorm, nameNorm, name);
    }

    // ---- Búsqueda --------------------------------------------------------

    private ResolvedAccount findByTerceroRef(String companyId, TerceroType type, String nameNorm) {
        List<ResolvedAccount> rows = jdbcTemplate.query("""
                SELECT id, code, name FROM accounting_accounts
                 WHERE company_id = ?
                   AND tercero_type = ?
                   AND UPPER(tercero_ref) = ?
                   AND active = TRUE
                 LIMIT 1
                """,
                (rs, n) -> new ResolvedAccount(
                        rs.getString("id"), rs.getString("code"), rs.getString("name")),
                companyId, type.name().toLowerCase(Locale.ROOT), nameNorm);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private ResolvedAccount findByTerceroNif(String companyId, TerceroType type, String nifNorm) {
        List<ResolvedAccount> rows = jdbcTemplate.query("""
                SELECT id, code, name FROM accounting_accounts
                 WHERE company_id = ?
                   AND tercero_type = ?
                   AND UPPER(tercero_nif) = ?
                   AND active = TRUE
                 LIMIT 1
                """,
                (rs, n) -> new ResolvedAccount(
                        rs.getString("id"), rs.getString("code"), rs.getString("name")),
                companyId, type.name().toLowerCase(Locale.ROOT), nifNorm);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private ResolvedAccount findByNameLike(String companyId, TerceroType type, String nameNorm) {
        String fragment = nameNorm.substring(0, Math.min(20, nameNorm.length()));
        List<ResolvedAccount> rows = jdbcTemplate.query("""
                SELECT id, code, name FROM accounting_accounts
                 WHERE company_id = ?
                   AND code LIKE ?
                   AND is_standard = FALSE
                   AND UPPER(name) LIKE ?
                   AND active = TRUE
                 LIMIT 1
                """,
                (rs, n) -> new ResolvedAccount(
                        rs.getString("id"), rs.getString("code"), rs.getString("name")),
                companyId, type.prefix + "%", "%" + fragment + "%");
        return rows.isEmpty() ? null : rows.get(0);
    }

    private ResolvedAccount findGenericParent(String companyId, TerceroType type) {
        List<ResolvedAccount> rows = jdbcTemplate.query("""
                SELECT id, code, name FROM accounting_accounts
                 WHERE company_id = ?
                   AND code = ?
                   AND active = TRUE
                 LIMIT 1
                """,
                (rs, n) -> new ResolvedAccount(
                        rs.getString("id"), rs.getString("code"), rs.getString("name")),
                companyId, type.parentCode);
        if (!rows.isEmpty()) return rows.get(0);
        // Buscar por prefijo (e.g. 4000 o 4300 ya creados)
        List<ResolvedAccount> alt = jdbcTemplate.query("""
                SELECT id, code, name FROM accounting_accounts
                 WHERE company_id = ?
                   AND code LIKE ?
                   AND active = TRUE
                 ORDER BY LENGTH(code), code
                 LIMIT 1
                """,
                (rs, n) -> new ResolvedAccount(
                        rs.getString("id"), rs.getString("code"), rs.getString("name")),
                companyId, type.parentCode + "%");
        return alt.isEmpty() ? new ResolvedAccount(null, type.parentCode, type.parentName) : alt.get(0);
    }

    // ---- Creación --------------------------------------------------------

    /**
     * Crea sub-cuenta nueva. El código se genera según la config de la
     * empresa ({@code tercero_account_length} y {@code tercero_account_mode}):
     * <ul>
     *   <li><b>SEQUENTIAL</b>: siguiente número padded con ceros hasta
     *       cubrir la longitud configurada (ej. longitud=7 → 4000001).</li>
     *   <li><b>BY_NIF</b>: dígitos del NIF (sin letras), padded con ceros
     *       a la izquierda. Si el NIF excede la longitud configurada, esa
     *       cuenta amplía su tamaño para preservar unicidad — la siguiente
     *       vuelve a respetar la config. Si no hay NIF, fallback SEQUENTIAL.</li>
     * </ul>
     * Si la cuenta padre (400/430) no existe la crea también.
     */
    private ResolvedAccount createSubaccount(String companyId, TerceroType type,
                                              String nifNorm, String nameNorm,
                                              String originalName) {
        // 1. Asegurar cuenta padre.
        String parentId = ensureParentAccount(companyId, type);

        // 2. Leer config de la empresa.
        TerceroConfig cfg = loadConfig(companyId);
        int suffixDigits = Math.max(1, cfg.totalLength() - type.prefix.length());
        String newCode = buildSubaccountCode(companyId, type, nifNorm, suffixDigits, cfg.mode());

        // 3. Nombre humano.
        String displayName = originalName == null || originalName.isBlank()
                ? type.parentName + " " + newCode
                : (type.parentName + " - " + originalName.trim());
        if (displayName.length() > 180) displayName = displayName.substring(0, 180);

        // 4. INSERT defensivo contra carrera de concurrencia.
        String accountId = UUID.randomUUID().toString();
        try {
            jdbcTemplate.update("""
                    INSERT INTO accounting_accounts
                        (id, company_id, code, name, account_type, parent_account_id,
                         active, is_standard, tercero_type, tercero_ref, tercero_nif)
                    VALUES (?, ?, ?, ?, ?, ?, TRUE, FALSE, ?, ?, ?)
                    """,
                    accountId, companyId, newCode, displayName, type.accountType, parentId,
                    type.name().toLowerCase(Locale.ROOT),
                    nameNorm.isEmpty() ? null : nameNorm,
                    nifNorm.isEmpty() ? null : nifNorm);
            return new ResolvedAccount(accountId, newCode, displayName);
        } catch (org.springframework.dao.DuplicateKeyException dup) {
            // Otra petición creó la sub-cuenta a la vez — volver a buscar.
            if (!nameNorm.isEmpty()) {
                ResolvedAccount r = findByTerceroRef(companyId, type, nameNorm);
                if (r != null) return r;
            }
            if (!nifNorm.isEmpty()) {
                ResolvedAccount r = findByTerceroNif(companyId, type, nifNorm);
                if (r != null) return r;
            }
            throw dup;
        }
    }

    private String ensureParentAccount(String companyId, TerceroType type) {
        List<String> ids = jdbcTemplate.query("""
                SELECT id FROM accounting_accounts
                 WHERE company_id = ? AND code = ?
                 LIMIT 1
                """,
                (rs, n) -> rs.getString("id"),
                companyId, type.parentCode);
        if (!ids.isEmpty()) return ids.get(0);
        String parentId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO accounting_accounts
                    (id, company_id, code, name, account_type, active, is_standard)
                VALUES (?, ?, ?, ?, ?, TRUE, TRUE)
                """,
                parentId, companyId, type.parentCode, type.parentName, type.accountType);
        return parentId;
    }

    // ---- Configuración por empresa --------------------------------------

    /** Modo de generación del sufijo de sub-cuenta. */
    public enum SubaccountMode { SEQUENTIAL, BY_NIF }

    /** Config leída de companies — usada para generar el código. */
    private record TerceroConfig(int totalLength, SubaccountMode mode) {}

    /** Carga la config y aplica defaults si las columnas no existen aún. */
    private TerceroConfig loadConfig(String companyId) {
        try {
            return jdbcTemplate.query("""
                    SELECT tercero_account_length, tercero_account_mode
                      FROM companies WHERE id = ? LIMIT 1
                    """, (rs, n) -> {
                int len = rs.getInt("tercero_account_length");
                if (len < 6 || len > 12) len = 7;
                String modeStr = rs.getString("tercero_account_mode");
                SubaccountMode mode = SubaccountMode.SEQUENTIAL;
                if (modeStr != null) {
                    try {
                        mode = SubaccountMode.valueOf(modeStr.trim().toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException ignored) {}
                }
                return new TerceroConfig(len, mode);
            }, companyId).stream().findFirst()
                    .orElse(new TerceroConfig(7, SubaccountMode.SEQUENTIAL));
        } catch (org.springframework.jdbc.BadSqlGrammarException ex) {
            // V56 aún no aplicada (entorno legacy) — usar defaults.
            return new TerceroConfig(7, SubaccountMode.SEQUENTIAL);
        }
    }

    /**
     * Construye el código según el modo. Hace el chequeo de unicidad
     * (si el código generado ya existe, en BY_NIF mantiene NIF pero añade
     * sufijo numérico de desambiguación; en SEQUENTIAL pasa al siguiente).
     */
    private String buildSubaccountCode(String companyId, TerceroType type,
                                         String nifNorm, int suffixDigits,
                                         SubaccountMode mode) {
        if (mode == SubaccountMode.BY_NIF && nifNorm != null && !nifNorm.isEmpty()) {
            String digits = nifNorm.replaceAll("\\D", "");
            if (!digits.isEmpty()) {
                // Padding/no-truncar: si NIF excede longitud configurada,
                // se usa el NIF completo (preserva unicidad).
                String suffix = digits.length() >= suffixDigits
                        ? digits
                        : String.format(Locale.ROOT, "%" + suffixDigits + "s", digits)
                                .replace(' ', '0');
                String candidate = type.prefix + suffix;
                if (!codeExists(companyId, candidate)) return candidate;
                // Colisión rara (mismo NIF, dos clientes/proveedores
                // distintos): desambiguar con sufijo numérico -01, -02…
                for (int i = 1; i < 99; i++) {
                    String alt = candidate + String.format(Locale.ROOT, "%02d", i);
                    if (!codeExists(companyId, alt)) return alt;
                }
            }
        }

        // SEQUENTIAL (o BY_NIF sin NIF → fallback).
        Integer maxNum = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(CAST(SUBSTRING(code, ?) AS UNSIGNED)), 0)
                  FROM accounting_accounts
                 WHERE company_id = ?
                   AND code LIKE ?
                   AND LENGTH(code) > ?
                """, Integer.class,
                type.prefix.length() + 1, companyId,
                type.prefix + "%", type.prefix.length());
        int next = (maxNum == null ? 0 : maxNum) + 1;
        return type.prefix + String.format(Locale.ROOT, "%0" + suffixDigits + "d", next);
    }

    private boolean codeExists(String companyId, String code) {
        Integer n = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM accounting_accounts
                 WHERE company_id = ? AND code = ?
                """, Integer.class, companyId, code);
        return n != null && n > 0;
    }

    // ---- Normalización ---------------------------------------------------

    private static String normalize(String s) {
        if (s == null) return "";
        String t = s.trim().toUpperCase(Locale.ROOT);
        // Quitar acentos.
        t = java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        // Colapsar espacios.
        t = t.replaceAll("\\s+", " ");
        return t;
    }

    private static String normalizeNif(String s) {
        if (s == null) return "";
        return s.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s\\-\\.]", "");
    }
}
