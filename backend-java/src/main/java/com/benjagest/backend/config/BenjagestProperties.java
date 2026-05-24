package com.benjagest.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "benjagest")
public record BenjagestProperties(String apiVersion) {
}
