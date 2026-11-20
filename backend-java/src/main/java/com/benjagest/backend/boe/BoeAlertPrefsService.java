package com.benjagest.backend.boe;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * BOE-FILTRO — lee y guarda las preferencias de avisos del BOE de una empresa
 * ({@code boe_alert_prefs}, V183).
 *
 * <p>Sin fila guardada devuelve {@link BoeAlertPrefs#defaults()}: quien no haya
 * configurado nada sigue recibiendo los avisos útiles, no cero.
 *
 * <p>Recibe el {@code companyId} por PARÁMETRO y no por {@code TenantContext}:
 * el principal consumidor es el cron diario de {@code BoeAlertService}, que
 * corre en el hilo {@code scheduling-1} sin petición HTTP. Es exactamente la
 * trampa que describe CLAUDE.md §10.bis y la que costó el bloque REC-CRON-AUTH.
 */
@Service
public class BoeAlertPrefsService {

    private final JdbcTemplate jdbc;

    public BoeAlertPrefsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public BoeAlertPrefs get(String companyId) {
        List<BoeAlertPrefs> rows = jdbc.query("""
                SELECT enabled, sections, departments, keywords
                  FROM boe_alert_prefs
                 WHERE company_id = ?
                """, (rs, n) -> new BoeAlertPrefs(
                        rs.getBoolean("enabled"),
                        BoeAlertPrefs.parseList(rs.getString("sections"),
                                BoeAlertPrefs.DEFAULT_SECTIONS),
                        BoeAlertPrefs.parseList(rs.getString("departments"), List.of()),
                        BoeAlertPrefs.parseList(rs.getString("keywords"),
                                BoeAlertPrefs.DEFAULT_KEYWORDS)),
                companyId);
        return rows.isEmpty() ? BoeAlertPrefs.defaults() : rows.get(0);
    }

    /**
     * Guarda (inserta o actualiza) las preferencias. Las listas se guardan
     * SIEMPRE como texto, también vacías: una cadena vacía es "no quiero
     * filtrar por esto" y hay que poder distinguirla del NULL de "nunca lo
     * configuré" (ver la V183).
     */
    public BoeAlertPrefs save(String companyId, BoeAlertPrefs prefs) {
        jdbc.update("""
                INSERT INTO boe_alert_prefs (company_id, enabled, sections, departments, keywords)
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    enabled = VALUES(enabled),
                    sections = VALUES(sections),
                    departments = VALUES(departments),
                    keywords = VALUES(keywords)
                """,
                companyId,
                prefs.enabled(),
                BoeAlertPrefs.joinSections(prefs.sections()),
                BoeAlertPrefs.joinList(prefs.departments()),
                BoeAlertPrefs.joinList(prefs.keywords()));
        return get(companyId);
    }

    /**
     * Ministerios vistos realmente en los avisos ya recibidos, para que la
     * pantalla ofrezca una lista que se mantiene sola. Una lista fija de
     * ministerios se quedaría obsoleta en cuanto el Gobierno renombre uno —
     * y lo hacen a menudo.
     */
    public List<String> knownDepartments() {
        return jdbc.queryForList("""
                SELECT DISTINCT department
                  FROM boe_alerts
                 WHERE department IS NOT NULL AND department <> ''
                 ORDER BY department
                """, String.class);
    }
}
