package com.benjagest.ui.service;

/**
 * Excepción que lanzan los ApiClients cuando reciben 401/403 sin cuerpo
 * que indique permisos específicos (típicamente JWT expirado o token
 * inválido). La UI la captura y muestra "Sesión expirada — vuelve a
 * iniciar sesión" en vez del JSON técnico crudo.
 *
 * <p>Es {@code RuntimeException} para que pase por los wrappers async
 * sin necesidad de declararla en cada signature.
 */
public class SessionExpiredException extends RuntimeException {
    public SessionExpiredException(String message) {
        super(message);
    }
}
