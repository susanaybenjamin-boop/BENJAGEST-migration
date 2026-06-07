package com.benjagest.backend.auth.pin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * El OWNER asigna o cambia el PIN de un empleado. Empleado se identifica
 * vía el {@code employeeId} en el path; el body solo lleva el PIN nuevo.
 */
public record SetPinRequest(
        @NotBlank @Pattern(regexp = "\\d{4,8}",
                message = "El PIN debe tener entre 4 y 8 dígitos") String pin
) {}
