package com.benjagest.backend.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Util one-shot. No es un test de comportamiento: solo imprime hashes
 * BCrypt para los passwords demo. El hash se copia a mano en V8.
 *
 * Cada ejecucion produce un hash distinto (salt aleatorio) pero todos
 * validan correctamente la misma contrasena. Una vez copiado a V8,
 * este test se mantiene por si queremos generar mas hashes en el
 * futuro (ej. para semillas de tests).
 *
 * Ejecutar: mvn -pl backend-java test -Dtest=BcryptHashGenerator
 */
class BcryptHashGenerator {

    @Test
    void print_bcrypt_for_demo_password() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        String password = "Benjamin123456$";
        String hash = encoder.encode(password);
        System.out.println("BCRYPT_HASH for " + password + " = " + hash);
        // Verificamos que el hash recien generado valida el password original.
        boolean ok = encoder.matches(password, hash);
        System.out.println("matches=" + ok);
    }
}
