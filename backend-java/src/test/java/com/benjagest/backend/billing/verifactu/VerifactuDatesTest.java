package com.benjagest.backend.billing.verifactu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** VF-QR-TOGGLE: fecha limite de emision sin QR segun el NIF. */
class VerifactuDatesTest {

    @Test
    void dniAutonomoCaducaEnJulio2027() {
        assertEquals(LocalDate.of(2027, 7, 1), VerifactuDates.qrOptOutDeadline("12345678Z"));
    }

    @Test
    void nieAutonomoCaducaEnJulio2027() {
        assertEquals(LocalDate.of(2027, 7, 1), VerifactuDates.qrOptOutDeadline("X1234567L"));
        assertEquals(LocalDate.of(2027, 7, 1), VerifactuDates.qrOptOutDeadline("Y1234567X"));
        assertEquals(LocalDate.of(2027, 7, 1), VerifactuDates.qrOptOutDeadline("Z1234567R"));
    }

    @Test
    void sociedadCaducaEnEnero2027() {
        assertEquals(LocalDate.of(2027, 1, 1), VerifactuDates.qrOptOutDeadline("B12345678"));
        assertEquals(LocalDate.of(2027, 1, 1), VerifactuDates.qrOptOutDeadline("A12345678"));
        assertEquals(LocalDate.of(2027, 1, 1), VerifactuDates.qrOptOutDeadline("  b12345678  "));
    }

    @Test
    void sinNifAplicaLaFechaMasTempranaPorPrudencia() {
        assertEquals(LocalDate.of(2027, 1, 1), VerifactuDates.qrOptOutDeadline(null));
        assertEquals(LocalDate.of(2027, 1, 1), VerifactuDates.qrOptOutDeadline("  "));
    }
}
