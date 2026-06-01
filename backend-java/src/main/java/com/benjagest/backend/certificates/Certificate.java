package com.benjagest.backend.certificates;

import java.time.Instant;

/**
 * Representacion en memoria de una fila de `digital_certificates`
 * (.p12 / .pfx subido por la empresa para firmar VeriFactu, comunicar
 * con AEAT, DEHu, SS, etc.).
 *
 * Los campos sensibles (password, certificate_data) NUNCA salen al
 * cliente: el Service los expone como un record reducido sin esos
 * campos. Aqui se guardan en claro porque es la representacion interna
 * tras descifrar; en el Repository, antes de persistir, vuelven a
 * cifrarse con el StringEncryptor (Jasypt).
 *
 * Decision 7 de architecture: el cifrado vive en aplicacion (Jasypt),
 * no en MariaDB. Si alguien hace un dump de la BD no se lleva el
 * keystore en claro.
 */
public record Certificate(
        String id,
        String companyId,
        String alias,
        String certificateType,
        String subjectName,
        String subjectTaxIdentifier,
        String passwordPlaintext,
        String storagePath,
        String certificateDataBase64,
        Instant validFrom,
        Instant validTo,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {
}
