package com.benjagest.backend.settings;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record TestEmailRequest(@NotBlank @Email String recipient) {
}
