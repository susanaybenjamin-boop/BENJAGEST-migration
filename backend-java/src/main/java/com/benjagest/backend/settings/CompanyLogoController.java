package com.benjagest.backend.settings;

import com.benjagest.backend.auth.RequiresRole;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * PORT-4 LOGO — Endpoints del logo de empresa. Bajo
 * {@code /api/settings/company/logo}. Solo OWNER/ADMIN modifican; GET
 * lo puede ver cualquier rol para mostrar el logo en la app.
 */
@RestController
@RequestMapping("/api/settings/company/logo")
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
public class CompanyLogoController {

    private final CompanyLogoService service;

    public CompanyLogoController(CompanyLogoService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresRole({"OWNER", "ADMIN"})
    public CompanyLogoService.LogoInfo upload(@RequestPart("file") MultipartFile file) {
        return service.upload(file);
    }

    @GetMapping
    public ResponseEntity<byte[]> get() {
        byte[] bytes = service.read();
        if (bytes == null) return ResponseEntity.noContent().build();
        // Tipo MIME: si los primeros bytes son PNG, png; si no, jpeg.
        MediaType type = isPng(bytes) ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG;
        return ResponseEntity.ok()
                .contentType(type)
                .header("Cache-Control", "no-cache")
                .body(bytes);
    }

    @DeleteMapping
    @RequiresRole({"OWNER", "ADMIN"})
    public void delete() {
        service.delete();
    }

    private static boolean isPng(byte[] bytes) {
        return bytes.length >= 8
                && (bytes[0] & 0xff) == 0x89 && bytes[1] == 0x50
                && bytes[2] == 0x4E && bytes[3] == 0x47;
    }
}
