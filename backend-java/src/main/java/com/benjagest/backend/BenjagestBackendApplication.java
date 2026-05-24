package com.benjagest.backend;

import com.benjagest.backend.config.BenjagestProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(BenjagestProperties.class)
public class BenjagestBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(BenjagestBackendApplication.class, args);
    }
}
