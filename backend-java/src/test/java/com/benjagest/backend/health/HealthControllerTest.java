package com.benjagest.backend.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.benjagest.backend.config.BenjagestProperties;
import org.junit.jupiter.api.Test;

class HealthControllerTest {

    @Test
    void returnsBackendHealth() {
        HealthController controller = new HealthController(new BenjagestProperties("test"));

        HealthResponse response = controller.health();

        assertThat(response.status()).isEqualTo("UP");
        assertThat(response.service()).isEqualTo("benjagest-backend");
        assertThat(response.apiVersion()).isEqualTo("test");
        assertThat(response.timestamp()).isNotNull();
    }
}
