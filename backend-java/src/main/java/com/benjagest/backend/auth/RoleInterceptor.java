package com.benjagest.backend.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Lee @RequiresRole del handler (metodo o controller) y devuelve 403
 * si el rol del usuario en la empresa activa no esta en la whitelist.
 *
 * Si no hay usuario autenticado, devuelve 401 (no 403). Que el endpoint
 * pueda ser publico o no es decision de SecurityConfig; aqui, si llega
 * la peticion con anotacion @RequiresRole, asumimos que requiere sesion.
 *
 * Orden de checkeo: primero la anotacion del metodo (mas especifica),
 * si no esta, la de la clase. Si ninguna declara @RequiresRole, pasa.
 */
@Component
public class RoleInterceptor implements HandlerInterceptor {

    private final CurrentUserService currentUserService;

    public RoleInterceptor(CurrentUserService currentUserService) {
        this.currentUserService = currentUserService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }

        RequiresRole annotation = method.getMethodAnnotation(RequiresRole.class);
        if (annotation == null) {
            annotation = method.getBeanType().getAnnotation(RequiresRole.class);
        }
        if (annotation == null) {
            return true;
        }

        AuthenticatedUser user = currentUserService.require();
        String role = user.roleInActiveCompany();
        if (role == null || Arrays.stream(annotation.value()).noneMatch(role::equalsIgnoreCase)) {
            // Mensaje detallado: ayuda al asesor a entender por qué fue
            // denegado (caso típico: empresario con rol EMPLOYEE intenta
            // acción que solo OWNER/ACCOUNTANT pueden).
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Tu rol '" + (role == null ? "<sin rol>" : role)
                            + "' no tiene permiso para esta accion. "
                            + "Roles permitidos: " + String.join(",", annotation.value())
                            + ". Usuario: " + user.email()
                            + " (globalRole=" + user.globalRole() + ")."
            );
        }
        return true;
    }
}
