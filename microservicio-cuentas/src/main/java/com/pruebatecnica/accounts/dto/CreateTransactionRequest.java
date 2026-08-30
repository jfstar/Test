package com.pruebatecnica.accounts.dto;

import com.pruebatecnica.accounts.model.MovementType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Datos para registrar un movimiento sobre una cuenta")
public class CreateTransactionRequest {

    @NotNull(message = "tipo es obligatorio (CREDIT o DEBIT)")
    @Schema(description = "Tipo de movimiento", example = "DEBIT")
    private MovementType tipo;

    @NotNull(message = "monto es obligatorio")
    @DecimalMin(value = "0.01", message = "monto debe ser mayor que 0")
    @Schema(description = "Monto del movimiento, debe ser mayor que 0", example = "25.50")
    private BigDecimal monto;
}
