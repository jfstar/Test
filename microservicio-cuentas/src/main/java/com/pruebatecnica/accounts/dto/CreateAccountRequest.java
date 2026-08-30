package com.pruebatecnica.accounts.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Datos para crear una cuenta nueva")
public class CreateAccountRequest {

    @NotBlank(message = "titular es obligatorio")
    @Size(max = 150, message = "titular no puede exceder 150 caracteres")
    @Schema(description = "Nombre del titular de la cuenta", example = "Maria Perez")
    private String titular;

    @NotNull(message = "saldoInicial es obligatorio")
    @DecimalMin(value = "0.00", inclusive = true, message = "saldoInicial no puede ser negativo")
    @Schema(description = "Saldo inicial de la cuenta, debe ser >= 0", example = "100.00")
    private BigDecimal saldoInicial;
}
