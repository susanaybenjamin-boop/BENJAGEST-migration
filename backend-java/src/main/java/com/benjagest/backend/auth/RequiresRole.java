package com.benjagest.backend.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca un endpoint (o un controller entero) como restringido a una
 * whitelist de roles dentro de la empresa activa. Si el rol de la
 * membership actual del usuario (claim roleInActiveCompany del JWT)
 * no esta en la whitelist, RoleInterceptor devuelve 403 antes de
 * ejecutar el metodo.
 *
 * Uso:
 *   @RestController
 *   @RequiresRole({"OWNER", "ADMIN"})
 *   public class CompanyDataController { ... }
 *
 * Combinable con @RequiresModule: ambos interceptors corren, primero
 * el de modulo y despues el de rol. Si la empresa no tiene el modulo,
 * 403 sin llegar a comprobar el rol.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresRole {
    String[] value();
}
