package com.benjagest.backend.workspace;

import jakarta.validation.constraints.NotBlank;

public record PinLoginRequest(@NotBlank String pin) {
}
