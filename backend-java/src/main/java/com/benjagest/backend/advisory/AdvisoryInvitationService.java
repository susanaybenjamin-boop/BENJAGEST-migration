package com.benjagest.backend.advisory;

import com.benjagest.backend.audit.AuditService;
import com.benjagest.backend.auth.AuthenticatedUser;
import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.tenant.TenantContext;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * Lógica de invitaciones asesoría↔cliente.
 *
 * Hay tres puntos de vista:
 *
 *   - **Asesoría** (tenant = INTERNAL/ADVISORY): crea invitaciones,
 *     ve las suyas, revoca.
 *   - **Empresario** (tenant = CLIENT): ve las invitaciones que le
 *     apuntan (por NIF o email), acepta o rechaza, puede
 *     desvincularse de su asesoría.
 *   - **Token público**: cualquiera con el token puede aceptar SI Y
 *     SOLO SI el NIF o email coincide con su sesión. Esto cubre el
 *     caso del email reenviado.
 *
 * Decisiones:
 *   - El token es URL-safe (alfabeto base62) de 32 chars (~190 bits).
 *   - Caducidad por defecto: 7 días desde creación.
 *   - Al aceptar, se EXIGE que la empresa actual del empresario NO
 *     tenga parent_company_id ya seteado. Bloquea cambios de
 *     asesoría sin desvinculación previa explícita.
 */
@Service
public class AdvisoryInvitationService {

    private static final long EXPIRATION_DAYS = 7;
    private static final String TOKEN_ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int TOKEN_LENGTH = 32;
    private static final SecureRandom RNG = new SecureRandom();

    private final AdvisoryInvitationRepository repository;
    private final JdbcTemplate jdbcTemplate;
    private final CurrentUserService currentUserService;
    private final TenantContext tenantContext;
    private final AuditService auditService;
    private final com.benjagest.backend.advisory.notifications.AdvisoryNotificationService notificationService;
    private final com.benjagest.backend.realtime.RealtimeService realtime;

    public AdvisoryInvitationService(AdvisoryInvitationRepository repository,
                                       JdbcTemplate jdbcTemplate,
                                       CurrentUserService currentUserService,
                                       TenantContext tenantContext,
                                       AuditService auditService,
                                       com.benjagest.backend.advisory.notifications.AdvisoryNotificationService notificationService,
                                       com.benjagest.backend.realtime.RealtimeService realtime) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
        this.currentUserService = currentUserService;
        this.tenantContext = tenantContext;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.realtime = realtime;
    }

    /** NOTIF-RT — empuja "invitation" a las empresas cuyo NIF coincide con el invitado
     *  (la invitación se dirige por NIF/email, aún sin companyId hasta aceptar). */
    private void emitInvitationToNif(String nif) {
        if (nif == null || nif.isBlank()) return;
        try {
            for (String id : jdbcTemplate.queryForList(
                    "SELECT id FROM companies WHERE tax_identifier = ?", String.class, nif)) {
                realtime.publishToCompany(id, "invitation", "{}");
            }
        } catch (Exception ignored) {}
    }

    /**
     * Helper interno: notifica al asesor cuando ocurre un evento en
     * una invitación. Try/catch para que un fallo en notificaciones
     * NO tumbe el flujo principal de aceptación/rechazo de invitación.
     */
    private void notifyAdvisor(String advisoryCompanyId, String clientCompanyId,
                                String type, String severity,
                                String title, String message) {
        if (notificationService == null) return;
        try {
            notificationService.emit(
                    new com.benjagest.backend.advisory.notifications.AdvisoryNotificationService.EmitRequest(
                            advisoryCompanyId, clientCompanyId, type, severity,
                            title, message, null));
        } catch (Exception ex) {
            // log but swallow — el evento principal ya está auditado.
        }
    }

    // ==================== ASESORÍA ====================

    /** Crea una invitación. Llamada solo desde el tenant de la asesoría. */
    @Transactional
    public AdvisoryInvitation create(CreateRequest req) {
        if (!StringUtils.hasText(req.invitedEmail())
                && !StringUtils.hasText(req.invitedNif())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Se requiere email o NIF del invitado (o ambos)");
        }
        AuthenticatedUser user = currentUserService.require();
        String advisoryCompanyId = tenantContext.getCurrentCompanyId();
        String id = UUID.randomUUID().toString();
        String token = generateToken();
        Instant expiresAt = Instant.now().plus(EXPIRATION_DAYS, ChronoUnit.DAYS);

        AdvisoryInvitation invitation = new AdvisoryInvitation(
                id, advisoryCompanyId,
                normalizeEmail(req.invitedEmail()),
                normalizeNif(req.invitedNif()),
                blankToNull(req.invitedCompanyName()),
                null,
                token,
                AdvisoryInvitation.STATUS_PENDING,
                expiresAt,
                blankToNull(req.notes()),
                user.userId(),
                null, null,
                null, null
        );
        repository.insert(invitation);
        auditService.recordAdvisoryInvitationCreated(
                user.userId(), advisoryCompanyId, id, invitation.invitedNif(),
                invitation.invitedEmail());
        // NOTIF-RT: avisa al empresario destinatario (su lista de invitaciones).
        emitInvitationToNif(invitation.invitedNif());
        return invitation;
    }

    public List<AdvisoryInvitation> listForCurrentAdvisory() {
        return repository.listForAdvisory(tenantContext.getCurrentCompanyId());
    }

    @Transactional
    public void revoke(String id) {
        AdvisoryInvitation inv = repository.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Invitación no encontrada"));
        if (!Objects.equals(inv.advisoryCompanyId(), tenantContext.getCurrentCompanyId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Esta invitación no pertenece a tu asesoría");
        }
        if (!AdvisoryInvitation.STATUS_PENDING.equals(inv.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Solo se pueden revocar invitaciones PENDING");
        }
        repository.updateStatus(id, AdvisoryInvitation.STATUS_REVOKED);
        AuthenticatedUser user = currentUserService.require();
        auditService.recordAdvisoryInvitationRevoked(
                user.userId(), tenantContext.getCurrentCompanyId(), id);
        // NOTIF-RT: el empresario destinatario debe ver que desaparece de su lista.
        emitInvitationToNif(inv.invitedNif());
    }

    // ==================== EMPRESARIO ====================

    /**
     * Lista invitaciones pendientes dirigidas al empresario actual.
     * El "match" se hace contra el NIF de la empresa activa (tenant)
     * Y/O contra el email del usuario logueado.
     */
    public List<AdvisoryInvitation> listPendingForCurrentClient() {
        AuthenticatedUser user = currentUserService.require();
        String tenant = tenantContext.getCurrentCompanyId();
        String nif = findCompanyNif(tenant);
        return repository.listPending(nif, user.email());
    }

    @Transactional
    public AdvisoryInvitation accept(String token) {
        AdvisoryInvitation inv = repository.findByToken(token).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Invitación no encontrada"));
        validatePendingAndFresh(inv);

        AuthenticatedUser user = currentUserService.require();
        String tenant = tenantContext.getCurrentCompanyId();
        String tenantNif = findCompanyNif(tenant);

        // Validación cruzada: la invitación debe encajar con esta sesión.
        boolean nifMatch = StringUtils.hasText(inv.invitedNif())
                && StringUtils.hasText(tenantNif)
                && inv.invitedNif().equalsIgnoreCase(tenantNif);
        boolean emailMatch = StringUtils.hasText(inv.invitedEmail())
                && StringUtils.hasText(user.email())
                && inv.invitedEmail().equalsIgnoreCase(user.email());
        if (!nifMatch && !emailMatch) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "El NIF/email de la invitación no coincide con tu sesión");
        }

        // Bloqueo: si la empresa ya tiene asesoría asignada, exige
        // desvinculación previa.
        String currentParent = findCurrentParent(tenant);
        if (StringUtils.hasText(currentParent)) {
            if (currentParent.equals(inv.advisoryCompanyId())) {
                // ya vinculada con esa misma asesoría → idempotente
                repository.updateStatusAccepted(inv.id(), user.userId(), tenant, tenantNif);
                return repository.findById(inv.id()).orElseThrow();
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Tu empresa ya tiene asesoría asignada. Desvincúlate antes de aceptar otra.");
        }

        // Vincular y marcar ACCEPTED.
        jdbcTemplate.update("""
                UPDATE companies
                   SET parent_company_id = ?
                 WHERE id = ?
                """, inv.advisoryCompanyId(), tenant);
        repository.updateStatusAccepted(inv.id(), user.userId(), tenant, tenantNif);

        // Auto-sync a la cartera de clientes de la asesoría: el cliente
        // acaba de vincularse, así que se añade a `customers` para que
        // la asesoría pueda facturarle. Si ya existía (NIF coincidente
        // dentro de la misma asesoría), no duplica.
        try {
            syncClientIntoAdvisoryCustomerPortfolio(inv.advisoryCompanyId(), tenant);
        } catch (Exception ex) {
            // No bloqueamos el flujo de aceptación si falla el sync —
            // la vinculación principal ya está hecha y se puede
            // recuperar más tarde.
            System.err.println("[advisory] no se pudo sincronizar customer tras aceptar invitación: "
                    + ex.getMessage());
        }

        auditService.recordAdvisoryInvitationAccepted(
                user.userId(), tenant, inv.id(), inv.advisoryCompanyId());

        // Notifica al asesor que el cliente aceptó. Distinto del
        // audit_event (forense): aquí es accionable, aparece en la
        // bandeja del asesor con badge.
        notifyAdvisor(inv.advisoryCompanyId(), tenant,
                "INVITATION_ACCEPTED",
                com.benjagest.backend.advisory.notifications.AdvisoryNotificationService.SEVERITY_INFO,
                "Cliente aceptó la invitación",
                "El cliente con NIF " + (inv.invitedNif() == null ? "?" : inv.invitedNif())
                        + " ha aceptado la invitación. Ya puedes facturarle y gestionar sus libros.");

        // NOTIF-RT: la asesoría ve el nuevo cliente vinculado (cartera) y el
        // empresario ve desaparecer la invitación aceptada de su lista.
        realtime.publishToCompany(inv.advisoryCompanyId(), "clients", "{}");
        realtime.publishToCompany(tenant, "invitation", "{}");

        return repository.findById(inv.id()).orElseThrow();
    }

    /**
     * Sincroniza la empresa cliente recién vinculada con la cartera de
     * clientes (`customers`) de la asesoría. Idempotente: si ya hay un
     * registro con el mismo tax_identifier para esa asesoría, no
     * inserta nada.
     *
     * <p>Tras esto, la asesoría puede emitir facturas a esa empresa
     * desde su módulo F4 — antes solo podía gestionar sus datos pero
     * no facturarle.
     */
    private void syncClientIntoAdvisoryCustomerPortfolio(String advisoryCompanyId,
                                                            String clientCompanyId) {
        // ¿Ya existe en la cartera de la asesoría?
        Integer exists = jdbcTemplate.query("""
                SELECT COUNT(*)
                  FROM customers c
                  JOIN companies cli ON cli.id = ?
                 WHERE c.company_id = ?
                   AND c.tax_identifier = cli.tax_identifier
                """, rs -> rs.next() ? rs.getInt(1) : 0,
                clientCompanyId, advisoryCompanyId);
        if (exists != null && exists > 0) return;

        // Crear customer con los datos básicos de la empresa cliente.
        // Email/teléfono se rellenan desde companies si existen.
        try {
            jdbcTemplate.update("""
                    INSERT INTO customers (id, company_id, legal_name, trade_name,
                                            tax_identifier, customer_type, notes, active)
                    SELECT ?, ?, cli.legal_name, cli.trade_name,
                           cli.tax_identifier,
                           CASE WHEN cli.company_type = 'SELF_EMPLOYED'
                                THEN 'SELF_EMPLOYED'
                                ELSE 'COMPANY' END,
                           'Auto-creado al vincular como cliente de la asesoría',
                           TRUE
                      FROM companies cli
                     WHERE cli.id = ?
                    """,
                    UUID.randomUUID().toString(),
                    advisoryCompanyId,
                    clientCompanyId);
        } catch (org.springframework.dao.DuplicateKeyException dup) {
            // El UNIQUE(tax_identifier) global del schema V1 puede
            // hacer fallar si OTRA asesoría ya tiene a esta empresa
            // en su cartera. Bug de schema pre-existente — para no
            // bloquear el flujo, lo aceptamos y loguemos. Cuando se
            // arregle el constraint (UNIQUE(company_id, tax_identifier)),
            // este catch ya no se disparará.
            System.err.println("[advisory] customer ya existe por UNIQUE global de tax_identifier; "
                    + "no se duplica");
        }
    }

    @Transactional
    public void reject(String token) {
        AdvisoryInvitation inv = repository.findByToken(token).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Invitación no encontrada"));
        validatePendingAndFresh(inv);
        repository.updateStatus(inv.id(), AdvisoryInvitation.STATUS_REJECTED);
        AuthenticatedUser user = currentUserService.require();
        String clientTenant = tenantContext.getCurrentCompanyId();
        auditService.recordAdvisoryInvitationRejected(
                user.userId(), clientTenant, inv.id());
        // Notifica al asesor que el cliente rechazó. Severity WARNING
        // porque suele requerir acción comercial (llamar al cliente,
        // entender el motivo, reenviar invitación corregida).
        notifyAdvisor(inv.advisoryCompanyId(), clientTenant,
                "INVITATION_REJECTED",
                com.benjagest.backend.advisory.notifications.AdvisoryNotificationService.SEVERITY_WARNING,
                "Cliente rechazó la invitación",
                "El cliente con NIF " + (inv.invitedNif() == null ? "?" : inv.invitedNif())
                        + " ha rechazado la invitación. Considera contactarle para entender el motivo.");
        // NOTIF-RT: la invitación rechazada desaparece de la lista del empresario.
        realtime.publishToCompany(clientTenant, "invitation", "{}");
    }

    /**
     * El empresario rompe el vínculo con su asesoría actual.
     * Después de esto puede aceptar una invitación nueva.
     *
     * <p>Además de borrar {@code companies.parent_company_id}, busca la
     * invitación ACCEPTED que originó el vínculo y la marca como
     * UNLINKED. Así la pantalla de la asesoría refleja el estado real
     * (cliente desvinculado) en lugar de seguir mostrando "Aceptada"
     * para siempre.
     */
    @Transactional
    public void unlinkCurrentAdvisory() {
        AuthenticatedUser user = currentUserService.require();
        String tenant = tenantContext.getCurrentCompanyId();
        String currentParent = findCurrentParent(tenant);
        if (!StringUtils.hasText(currentParent)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Tu empresa no tiene asesoría asignada");
        }
        jdbcTemplate.update("""
                UPDATE companies
                   SET parent_company_id = NULL
                 WHERE id = ?
                """, tenant);

        // Sincronizar la invitación que originó el vínculo. Si no se
        // encuentra (caso raro: vínculo manual previo a las
        // invitaciones), se omite sin error — la auditoría
        // recordAdvisoryUnlinked deja constancia del unlink igualmente.
        repository.findLatestAcceptedByPair(currentParent, tenant)
                .ifPresent(inv -> repository.updateStatusUnlinked(inv.id()));

        auditService.recordAdvisoryUnlinked(user.userId(), tenant, currentParent);
        // NOTIF-RT: la asesoría ve desaparecer al cliente desvinculado de su cartera.
        realtime.publishToCompany(currentParent, "clients", "{}");
    }

    /**
     * Información de la asesoría vinculada (si existe), pensado para la
     * UI del empresario que muestra "Mi asesoría: X".
     */
    public Optional<LinkedAdvisoryInfo> findCurrentLinkedAdvisory() {
        String tenant = tenantContext.getCurrentCompanyId();
        String parent = findCurrentParent(tenant);
        if (!StringUtils.hasText(parent)) return Optional.empty();
        return jdbcTemplate.query("""
                SELECT id, legal_name, trade_name, tax_identifier, email
                  FROM companies
                 WHERE id = ?
                """,
                (rs, n) -> new LinkedAdvisoryInfo(
                        rs.getString("id"),
                        rs.getString("legal_name"),
                        rs.getString("trade_name"),
                        rs.getString("tax_identifier"),
                        rs.getString("email")),
                parent).stream().findFirst();
    }

    // ==================== HELPERS ====================

    private void validatePendingAndFresh(AdvisoryInvitation inv) {
        if (!AdvisoryInvitation.STATUS_PENDING.equals(inv.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "La invitación no está pendiente (estado=" + inv.status() + ")");
        }
        if (inv.expiresAt() != null && inv.expiresAt().isBefore(Instant.now())) {
            repository.updateStatus(inv.id(), AdvisoryInvitation.STATUS_EXPIRED);
            throw new ResponseStatusException(HttpStatus.GONE,
                    "La invitación ha caducado");
        }
    }

    private String findCompanyNif(String companyId) {
        if (!StringUtils.hasText(companyId)) return null;
        // ResultSetExtractor (no RowMapper) para tolerar NULLs sin NPE:
        // el RowMapper anterior, al devolver null para una columna NULL,
        // hacía que la lista contuviera [null] y findFirst() lanzaba NPE
        // en Optional.of(null). Con extractor controlamos el cursor.
        return jdbcTemplate.query("""
                SELECT tax_identifier FROM companies WHERE id = ?
                """,
                rs -> rs.next() ? rs.getString("tax_identifier") : null,
                companyId);
    }

    private String findCurrentParent(String companyId) {
        if (!StringUtils.hasText(companyId)) return null;
        return jdbcTemplate.query("""
                SELECT parent_company_id FROM companies WHERE id = ?
                """,
                rs -> rs.next() ? rs.getString("parent_company_id") : null,
                companyId);
    }

    private static String generateToken() {
        StringBuilder sb = new StringBuilder(TOKEN_LENGTH);
        for (int i = 0; i < TOKEN_LENGTH; i++) {
            sb.append(TOKEN_ALPHABET.charAt(RNG.nextInt(TOKEN_ALPHABET.length())));
        }
        return sb.toString();
    }

    private String normalizeEmail(String v) {
        return v == null || v.isBlank() ? null : v.trim().toLowerCase();
    }
    private String normalizeNif(String v) {
        return v == null || v.isBlank() ? null : v.trim().toUpperCase();
    }
    private String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    // ==================== DTOs ====================

    public record CreateRequest(
            String invitedEmail,
            String invitedNif,
            String invitedCompanyName,
            String notes
    ) {}

    public record LinkedAdvisoryInfo(
            String id,
            String legalName,
            String tradeName,
            String taxIdentifier,
            String email
    ) {}
}
