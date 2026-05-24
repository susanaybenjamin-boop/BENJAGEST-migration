package com.benjagest.backend.customer;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CustomerCreateRequest(
        @NotBlank @Size(max = 180) String legalName,
        @Size(max = 180) String tradeName,
        @NotBlank @Size(max = 32) String taxIdentifier,
        @Size(max = 160) String contactName,
        @Email @Size(max = 180) String email,
        @Size(max = 40) String phone
) {
}
