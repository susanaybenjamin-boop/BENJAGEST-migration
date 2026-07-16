package com.benjagest.backend.system;

import com.benjagest.backend.auth.RequiresRole;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * UPD-3 — Disparar la actualización desde la app, para que la haga el SERVICIO.
 *
 * <p><b>Sin {@code @RequiresModule}</b> a propósito: actualizar no depende de que
 * ningún módulo esté activo. Si la app está rota o a medias, esto tiene que
 * seguir funcionando.
 *
 * <p><b>Solo OWNER.</b> Esto acaba ejecutando un instalador como LocalSystem: es
 * lo más sensible del backend. Y por eso el endpoint <b>no acepta ninguna
 * ruta</b> — el servicio se descarga el MSI de la release oficial. Si aceptara
 * un path, cualquiera que llegase al 8080 (que escucha en 0.0.0.0, o sea, en
 * toda la LAN) podría hacer que un servicio SYSTEM ejecutara el instalador que
 * quisiera. Eso es una elevación de privilegios de libro.
 */
@RestController
@RequestMapping("/api/system/update")
@RequiresRole({"OWNER"})
public class SystemUpdateController {

    private final SystemUpdateService service;

    public SystemUpdateController(SystemUpdateService service) {
        this.service = service;
    }

    /** Arranca la actualización (descarga + instala). Devuelve enseguida. */
    @PostMapping
    public Map<String, Object> start() {
        service.start();
        SystemUpdateService.Status s = service.status();
        return Map.of("state", s.state().name());
    }

    /** Progreso, para la barra de la app. */
    @GetMapping("/status")
    public Map<String, Object> status() {
        SystemUpdateService.Status s = service.status();
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put("state", s.state().name());
        m.put("bytesDone", s.bytesDone());
        m.put("bytesTotal", s.bytesTotal());
        m.put("message", s.message());
        return m;
    }
}
