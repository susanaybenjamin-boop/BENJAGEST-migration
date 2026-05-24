package com.benjagest.backend.health;

import com.benjagest.backend.config.BenjagestProperties;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HealthController {

    private final BenjagestProperties properties;

    public HealthController(BenjagestProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse("UP", "benjagest-backend", properties.apiVersion(), Instant.now());
    }
}
