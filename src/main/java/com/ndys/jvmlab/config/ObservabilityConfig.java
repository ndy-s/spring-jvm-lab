package com.ndys.jvmlab.config;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

@Configuration
public class ObservabilityConfig {

    // Prometheus scrapes /actuator/prometheus every 5s; without this each scrape becomes a trace
    // and buries the interesting ones in Tempo.
    @Bean
    ObservationPredicate skipActuatorRequests() {
        return (name, context) -> {
            if (context instanceof ServerRequestObservationContext server) {
                return !server.getCarrier().getRequestURI().startsWith("/actuator");
            }
            return true;
        };
    }
}
