package com.benjagest.ui.model;

import java.util.List;

/** Resultado de generar las nóminas mensuales de un mes (PAY-RECURRENT). */
public record MonthlyRunEntry(int generated, int skipped, List<String> errors) {}
