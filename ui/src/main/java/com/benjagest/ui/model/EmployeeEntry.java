package com.benjagest.ui.model;

import java.time.LocalDate;

/** Empleado (record plano para la UI). */
public record EmployeeEntry(
        String id,
        String fullName,
        String taxIdentifier,
        String socialSecurityNumber,
        String email,
        String phone,
        LocalDate birthDate,
        String gender,
        String maritalStatus,
        Integer dependentChildren,
        Integer dependentDisabled,
        String addressLine,
        String city,
        String province,
        String postalCode,
        String country,
        String iban,
        String workType,
        String ssRegime,
        LocalDate hireDate,
        LocalDate terminationDate,
        String terminationReason,
        boolean geolocationEnabled,
        boolean active
) {}
