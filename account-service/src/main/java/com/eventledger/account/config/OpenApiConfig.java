package com.eventledger.account.config;

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
    public OpenAPI accountServiceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Account Service API")
                        .version("v1")
                        .description("Internal service owning account balances and transaction history. "
                                + "Not exposed to external clients directly — called only by the Event "
                                + "Gateway. Applies transactions idempotently on eventId and computes "
                                + "balance as an order-independent running total (ΣCREDIT − ΣDEBIT).")
                        .contact(new Contact().name("Event Ledger Team")))
                .servers(List.of(new Server().url("http://localhost:8081").description("Local")));
    }
}
