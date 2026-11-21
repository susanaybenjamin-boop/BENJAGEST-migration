package com.benjagest.backend.aeat;

import com.benjagest.backend.auth.RequiresRole;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * SIM — simulador comparativo contra la realidad.
 *
 * <pre>
 *   POST /api/aeat/simulation   {year, quarter, lines:[...]}
 * </pre>
 *
 * <p>Solo POST: la simulación lleva una lista de compras previstas en el
 * cuerpo, no cabe en una URL. <b>No escribe nada</b> — es una calculadora.
 *
 * <p>Roles: es una herramienta de la ASESORÍA para aconsejar al cliente, así
 * que se queda en OWNER/ADMIN/ACCOUNTANT. No lleva EMPLOYEE, a diferencia de
 * los endpoints operacionales: no es trabajo del día a día y enseña una
 * fotografía fiscal completa del negocio.
 */
@RestController
@RequestMapping("/api/aeat/simulation")
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
public class TaxSimulationController {

    private final TaxSimulationService service;

    public TaxSimulationController(TaxSimulationService service) {
        this.service = service;
    }

    /** Una línea tal y como llega del cliente. */
    public record LinePayload(String concept, BigDecimal baseAmount, BigDecimal vatRate,
                              BigDecimal vatDeductiblePct, Boolean irpfDeductible) {}

    public record SimulationRequest(Integer year, Integer quarter, List<LinePayload> lines) {}

    @PostMapping
    public TaxSimulationService.SimulationResult simulate(@RequestBody SimulationRequest body) {
        int year = body.year() == null ? java.time.LocalDate.now().getYear() : body.year();
        int quarter = body.quarter() == null ? currentQuarter() : body.quarter();
        if (quarter < 1 || quarter > 4) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Trimestre fuera de rango");
        }
        if (year < 1990 || year > 2999) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ejercicio fuera de rango");
        }

        List<TaxSimulationService.SimLine> lines = new ArrayList<>();
        for (LinePayload p : body.lines() == null ? List.<LinePayload>of() : body.lines()) {
            if (p == null) continue;
            BigDecimal base = p.baseAmount();
            // Las lineas en blanco se ignoran en silencio: la pantalla viene con
            // filas de ejemplo vacias y obligar al asesor a borrarlas antes de
            // simular seria una friccion tonta.
            if (base == null || base.signum() == 0) continue;
            if (base.signum() < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "El importe de una compra no puede ser negativo");
            }
            lines.add(new TaxSimulationService.SimLine(
                    p.concept(), base,
                    p.vatRate() == null ? BigDecimal.ZERO : p.vatRate(),
                    p.vatDeductiblePct(),
                    p.irpfDeductible() == null || p.irpfDeductible()));
        }
        return service.simulate(year, quarter, lines);
    }

    private static int currentQuarter() {
        return (java.time.LocalDate.now().getMonthValue() - 1) / 3 + 1;
    }
}
