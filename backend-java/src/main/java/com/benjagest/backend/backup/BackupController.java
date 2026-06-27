package com.benjagest.backend.backup;

import com.benjagest.backend.auth.RequiresRole;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * API REST de los backups locales. Solo OWNER/ADMIN — quien tenga
 * acceso aquí tiene acceso al volcado completo de la BD.
 */
@RestController
@RequestMapping("/api/system/backup")
@RequiresRole({"OWNER", "ADMIN"})
public class BackupController {

    private final BackupService service;

    public BackupController(BackupService service) {
        this.service = service;
    }

    @PostMapping("/run")
    public Map<String, Object> runNow() {
        try {
            var path = service.run();
            return Map.of("ok", true, "file", path.toAbsolutePath().toString());
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Backup falló: " + ex.getMessage());
        }
    }

    @GetMapping("/list")
    public List<BackupService.BackupInfo> list() {
        try { return service.list(); }
        catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "No se pudo listar: " + ex.getMessage());
        }
    }

    /** Purga carpetas de ficheros de empresas que ya no existen en la BD. */
    @PostMapping("/purge-orphans")
    public Map<String, Object> purgeOrphans() {
        try {
            int deleted = service.purgeOrphanedCompanyFiles();
            return Map.of("ok", true, "deleted", deleted);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Purga falló: " + ex.getMessage());
        }
    }
}
