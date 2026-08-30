package com.pruebatecnica.accounts.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI accountsOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Microservicio de Cuentas y Movimientos")
                        .description("API para alta de cuentas y registro de movimientos (CREDIT/DEBIT) "
                                + "con validacion de saldo, idempotencia e integracion resiliente con un "
                                + "servicio externo de validacion. Prueba tecnica - Desarrollador Java Senior.")
                        .version("v0.1.0")
                        .contact(new Contact().name("Equipo de Desarrollo")));
    }
}
