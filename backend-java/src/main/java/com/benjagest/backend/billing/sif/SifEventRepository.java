package com.benjagest.backend.billing.sif;

import com.benjagest.backend.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Acceso a sif_event_registry. Cada query filtra por TenantContext —
 * misma defensa multi-tenant que el resto del backend, aunque aqui
 * el riesgo es mayor: si una empresa viera eventos de otra romperia
 * el principio de aislamiento del SIF.
 *
 * Hay metodos sin company_id (insertSystemEvent) que se usan en los
 * hooks de arranque/parada de Spring, cuando aun no hay TenantContext:
 * ahi el companyId se pasa explicito (recorrer todas las empresas con
 * modalidad NO_VERIFACTU al arrancar).
 */
@Repository
public class SifEventRepository {

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;

    public SifEventRepository(JdbcTemplate jdbcTemplate, TenantContext tenantContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
    }

    /**
     * Hash del ULTIMO evento de la cadena para esta empresa. Devuelve
     * cadena vacia si la cadena esta vacia (primer evento). El orden
     * es por generated_at DESC + id DESC para desempates en el mismo
     * segundo.
     */
    public String findLastHashForCompany(String companyId) {
        List<String> matches = jdbcTemplate.query("""
                SELECT hash_current
                  FROM sif_event_registry
                 WHERE company_id = ?
                 ORDER BY generated_at DESC, id DESC
                 LIMIT 1
                """,
                (rs, rowNum) -> rs.getString("hash_current"),
                companyId);
        return matches.isEmpty() ? "" : matches.get(0);
    }

    /**
     * Inserta un evento con companyId explicito y generated_at
     * explicito (mismo truco que VerifactuRegistry para que el hash
     * sea verificable). Se usa tanto en eventos con TenantContext (el
     * service lo pasa) como en hooks de sistema sin tenant.
     */
    /**
     * Persiste la firma electronica de un evento (slice VF-SIGN). Lo
     * separamos del INSERT porque la firma puede llegar despues (cuando
     * VF-SIGN este desactivado o falle, el evento queda sin firma y un
     * job posterior puede reintentar).
     */
    public int setSignature(String eventId, String signatureXml) {
        return jdbcTemplate.update("""
                UPDATE sif_event_registry
                   SET signature_data = ?,
                       signed_at = CURRENT_TIMESTAMP,
                       status = 'SIGNED'
                 WHERE id = ?
                   AND company_id = ?
                """,
                signatureXml, eventId, tenantContext.getCurrentCompanyId()
        );
    }

    public void insert(String id, String companyId, String eventType, String payload,
                       String hashCurrent, String hashPrevious,
                       OffsetDateTime generationTime) {
        jdbcTemplate.update("""
                INSERT INTO sif_event_registry (
                    id, company_id, event_type, payload,
                    hash_current, hash_previous,
                    generated_at, status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING')
                """,
                id,
                companyId,
                eventType,
                payload,
                hashCurrent,
                hashPrevious,
                Timestamp.from(generationTime.toInstant())
        );
    }

    public List<SifEvent> findForCompany(String eventTypeFilter, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, company_id, event_type, payload,
                       hash_current, hash_previous, generated_at,
                       signed_at, signature_data, status
                  FROM sif_event_registry
                 WHERE company_id = ?
                """);
        List<Object> args = new java.util.ArrayList<>();
        args.add(tenantContext.getCurrentCompanyId());
        if (eventTypeFilter != null && !eventTypeFilter.isBlank()) {
            sql.append("   AND event_type = ?\n");
            args.add(eventTypeFilter.trim());
        }
        sql.append(" ORDER BY generated_at DESC, id DESC LIMIT ?");
        args.add(Math.min(Math.max(limit, 1), 500));
        return jdbcTemplate.query(sql.toString(), this::mapEvent, args.toArray());
    }

    /**
     * Cadena cronologica ASC para la verificacion. Lleva los campos
     * necesarios para recalcular el hash.
     */
    public List<ChainRow> findChainOrderedAsc() {
        return findChainOrderedAscForCompany(tenantContext.getCurrentCompanyId());
    }

    /**
     * Variante sin TenantContext — para jobs @Scheduled (VF-ANOMALY).
     */
    public List<ChainRow> findChainOrderedAscForCompany(String companyId) {
        return jdbcTemplate.query("""
                SELECT id, event_type, payload, hash_current, hash_previous, generated_at
                  FROM sif_event_registry
                 WHERE company_id = ?
                 ORDER BY generated_at ASC, id ASC
                """,
                this::mapChainRow,
                companyId
        );
    }

    /**
     * Lista de empresas con modalidad NO_VERIFACTU — usado por el
     * SifEventLifecycle al arrancar/parar Spring para emitir un evento
     * SYSTEM_START / SYSTEM_STOP por cada SIF activo. Ojo: lee sin
     * TenantContext (es una consulta de sistema), de ahi que no se
     * filtre por empresa actual.
     */
    public List<String> findCompaniesInNoVerifactu() {
        return jdbcTemplate.queryForList("""
                SELECT id
                  FROM companies
                 WHERE verifactu_modality = 'NO_VERIFACTU'
                """, String.class);
    }

    /**
     * NIF/CIF de una empresa concreta para componer el hash. Lookup
     * directo sin TenantContext (se usa en hooks de sistema con
     * companyId explicito que viene de findCompaniesInNoVerifactu).
     */
    public String findTaxIdentifier(String companyId) {
        List<String> nifs = jdbcTemplate.query("""
                SELECT tax_identifier
                  FROM companies
                 WHERE id = ?
                """,
                (rs, rowNum) -> rs.getString("tax_identifier"),
                companyId);
        return nifs.isEmpty() ? null : nifs.get(0);
    }

    private SifEvent mapEvent(ResultSet rs, int rowNum) throws SQLException {
        Timestamp gen = rs.getTimestamp("generated_at");
        Timestamp signed = rs.getTimestamp("signed_at");
        return new SifEvent(
                rs.getString("id"),
                rs.getString("company_id"),
                rs.getString("event_type"),
                rs.getString("payload"),
                rs.getString("hash_current"),
                rs.getString("hash_previous"),
                gen == null ? null : gen.toInstant(),
                signed == null ? null : signed.toInstant(),
                rs.getString("signature_data"),
                rs.getString("status")
        );
    }

    private ChainRow mapChainRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp gen = rs.getTimestamp("generated_at");
        return new ChainRow(
                rs.getString("id"),
                rs.getString("event_type"),
                rs.getString("payload"),
                rs.getString("hash_current"),
                rs.getString("hash_previous"),
                gen == null ? null : OffsetDateTime.ofInstant(gen.toInstant(), ZoneOffset.UTC)
        );
    }

    public record ChainRow(
            String id,
            String eventType,
            String payload,
            String hashCurrent,
            String hashPrevious,
            OffsetDateTime generatedAt
    ) {
    }

    /**
     * Eventos PENDING para reintento de firma (VF4). Trae los campos
     * canonicos para reconstruir el XML de firma identico al original.
     */
    public List<PendingEvent> findPendingForSigning(int limit) {
        return jdbcTemplate.query("""
                SELECT e.id, e.company_id, c.tax_identifier,
                       e.event_type, e.payload,
                       e.hash_current, e.hash_previous, e.generated_at
                  FROM sif_event_registry e
                  JOIN companies c ON c.id = e.company_id
                 WHERE e.status = 'PENDING'
                 ORDER BY e.generated_at ASC
                 LIMIT ?
                """,
                (rs, rowNum) -> new PendingEvent(
                        rs.getString("id"),
                        rs.getString("company_id"),
                        rs.getString("tax_identifier"),
                        rs.getString("event_type"),
                        rs.getString("payload"),
                        rs.getString("hash_current"),
                        rs.getString("hash_previous"),
                        rs.getTimestamp("generated_at") == null ? null
                                : OffsetDateTime.ofInstant(rs.getTimestamp("generated_at").toInstant(), ZoneOffset.UTC)),
                Math.min(Math.max(limit, 1), 200));
    }

    /**
     * Setter de firma con companyId explicito (sin TenantContext).
     */
    public int setSignatureForCompany(String eventId, String companyId, String signatureXml) {
        return jdbcTemplate.update("""
                UPDATE sif_event_registry
                   SET signature_data = ?,
                       signed_at = CURRENT_TIMESTAMP,
                       status = 'SIGNED'
                 WHERE id = ?
                   AND company_id = ?
                """,
                signatureXml, eventId, companyId);
    }

    public record PendingEvent(
            String id,
            String companyId,
            String taxIdentifier,
            String eventType,
            String payload,
            String hashCurrent,
            String hashPrevious,
            OffsetDateTime generatedAt
    ) {}
}
