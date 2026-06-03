package com.benjagest.backend;

import com.benjagest.backend.auth.JwtProperties;
import com.benjagest.backend.config.BenjagestProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling activa los hooks @Scheduled. Lo necesitan los jobs
// del Registro de Eventos del SIF (SUMMARY_6H, detección de anomalías
// VF-ANOMALY) y futuros retries VF4. Mantener cerca de @SpringBootApplication
// para que cualquier developer vea de un vistazo que hay scheduling.
@SpringBootApplication
@EnableConfigurationProperties({BenjagestProperties.class, JwtProperties.class})
@EnableScheduling
public class BenjagestBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(BenjagestBackendApplication.class, args);
    }
}
