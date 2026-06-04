package com.benjagest.backend.timeclock;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint publico de verificacion por CSV (RD 8/2019 art. 35.8).
 *
 * IMPORTANTE: no lleva @RequiresRole ni @RequiresModule por diseno —
 * el CSV se publica para que cualquier tercero (Inspeccion de Trabajo,
 * abogados, otros sistemas) pueda comprobar la autenticidad de un
 * fichaje sin necesidad de credenciales. El CSV NO es secreto:
 * identifica un fichaje concreto, no autoriza modificacion.
 *
 * Para que esto no abra una via de enumeracion (= recorrer todos los
 * CSV posibles hasta encontrar uno valido), el CSV es de 16
 * caracteres en alfabeto de 32 (~80 bits de entropia) — equivalente a
 * un token criptografico. La SecurityConfig debera tener una excepcion
 * para esta ruta (TODO documentar cuando se ajuste el security chain).
 */
@RestController
@RequestMapping("/api/public/timeclock")
public class TimeClockVerifyPublicController {

    private final TimeClockService service;

    public TimeClockVerifyPublicController(TimeClockService service) {
        this.service = service;
    }

    @GetMapping("/verify")
    public TimeClockEvent verify(@RequestParam("csv") String csvCode) {
        return service.verifyByCsv(csvCode);
    }
}
