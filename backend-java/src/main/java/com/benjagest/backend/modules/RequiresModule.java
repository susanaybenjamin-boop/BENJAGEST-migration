package com.benjagest.backend.modules;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca un endpoint (o todo un Controller) como dependiente de un
 * modulo del catalogo. Si la empresa activa NO tiene ese modulo
 * encendido en company_modules, el ModuleAccessInterceptor responde
 * 403 antes de que el metodo ejecute.
 *
 * Uso:
 *   @RequiresModule("issuers")
 *   @RestController
 *   public class IssuerController { ... }
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresModule {
    String value();
}
