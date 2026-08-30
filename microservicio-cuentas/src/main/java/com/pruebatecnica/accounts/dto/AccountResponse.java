package com.pruebatecnica.accounts.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Datos publicos de una cuenta")
public class AccountResponse {

    private UUID id;
    private String titular;
    private BigDecimal saldo;
    private Instant fechaCreacion;
}
