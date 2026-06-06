package com.benjagest.backend.tenant;

import com.benjagest.backend.workspace.DemoCompany;
import org.springframework.stereotype.Component;

/**
 * Implementacion del TenantContext basada en {@link ThreadLocal}.
 *
 * <p>Originalmente era {@code @RequestScope} con proxy, lo que funcionaba
 * bien para peticiones HTTP pero hacía imposible usarlo desde jobs
 * {@link org.springframework.scheduling.annotation.Scheduled @Scheduled}
 * y desde tareas asíncronas — al no haber request, fallaba el proxy.
 *
 * <p>El cambio a ThreadLocal mantiene exactamente el mismo comportamiento
 * para HTTP (Tomcat asigna un thread por petición, el
 * {@link TenantInterceptor} setea el companyId al inicio y la
 * {@code afterCompletion} lo limpia). Y permite que el
 * {@link com.benjagest.backend.accounting.recurring.RecurringTaskScheduler}
 * lo configure por cada tarea recurrente.
 *
 * <p>Si nadie ha llamado {@link #setCurrentCompanyId(String)} en el thread
 * actual, devuelve la empresa demo — compatibilidad con la UI antes del
 * login real.
 */
@Component
public class RequestScopedTenantContext implements TenantContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    @Override
    public String getCurrentCompanyId() {
        String v = CURRENT.get();
        return v != null ? v : DemoCompany.ID;
    }

    @Override
    public void setCurrentCompanyId(String companyId) {
        if (companyId == null || companyId.isBlank()) {
            CURRENT.remove();
        } else {
            CURRENT.set(companyId);
        }
    }
}
