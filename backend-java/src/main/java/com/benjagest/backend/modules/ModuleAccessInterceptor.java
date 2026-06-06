package com.benjagest.backend.modules;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Lee la anotacion @RequiresModule del handler (metodo o clase del
 * Controller) y, si la empresa activa NO tiene ese modulo encendido,
 * lanza 403 antes de ejecutar el metodo.
 *
 * Orden de checkeo: primero @RequiresModule del metodo (mas especifico),
 * si no esta, el de la clase. Si ninguno declara @RequiresModule, deja
 * pasar (el endpoint no esta protegido por modulo).
 *
 * Se registra en WebMvcConfig DESPUES del TenantInterceptor para que el
 * company_id ya este puesto cuando este interceptor consulta.
 */
@Component
public class ModuleAccessInterceptor implements HandlerInterceptor {

    private final ModuleAccessService accessService;

    public ModuleAccessInterceptor(ModuleAccessService accessService) {
        this.accessService = accessService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }

        RequiresModule annotation = method.getMethodAnnotation(RequiresModule.class);
        if (annotation == null) {
            annotation = method.getBeanType().getAnnotation(RequiresModule.class);
        }
        if (annotation == null) {
            return true;
        }

        String slug = annotation.value();
        if (!accessService.isEnabledForCurrentCompany(slug)) {
            String header = request.getHeader("X-Company-Id");
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "El modulo '" + slug + "' no esta activo para la empresa "
                            + (header == null ? "<sin X-Company-Id>" : header)
                            + ". Actívalo en Configuración → Módulos."
            );
        }
        return true;
    }
}
