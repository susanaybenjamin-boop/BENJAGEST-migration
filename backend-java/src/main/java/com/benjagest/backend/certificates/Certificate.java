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
 *
 * Campos uploadedBy* (V38): permiten distinguir si el certificado lo
 * subió el propio cliente (uploaded_by_company_id == companyId o NULL)
 * o su asesoría (uploaded_by_company_id == asesoría.id, distinto del
 * tenant donde vive el cert).
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
        String uploadedByUserId,
        String uploadedByCompanyId,
        Instant createdAt,
        Instant updatedAt
) {
}
