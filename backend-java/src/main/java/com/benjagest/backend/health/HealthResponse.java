package com.benjagest.backend.health;

import java.time.Instant;

public record HealthResponse(String status, String service, String apiVersion, Instant timestamp) {
}
