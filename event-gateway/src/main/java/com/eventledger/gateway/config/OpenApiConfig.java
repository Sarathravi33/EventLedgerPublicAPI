package com.eventledger.gateway.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI eventGatewayOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Event Gateway API")
                        .version("v1")
                        .description("Public-facing entry point for submitting transaction events. "
                                + "Enforces idempotency on eventId, tolerates out-of-order arrival, "
                                + "stores every event locally as an audit record, and degrades "
                                + "gracefully (never hangs, never 500s) when the internal Account "
                                + "Service is unreachable.")
                        .contact(new Contact().name("Event Ledger Team")))
                .servers(List.of(new Server().url("http://localhost:8082").description("Local")));
    }
}
