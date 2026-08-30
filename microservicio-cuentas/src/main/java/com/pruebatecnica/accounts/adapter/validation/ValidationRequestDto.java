package com.pruebatecnica.accounts.adapter.validation;

import com.pruebatecnica.accounts.model.MovementType;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Payload enviado al servicio externo de validacion. Deliberadamente
 * separado de los DTOs publicos del API (CreateTransactionRequest): el
 * contrato con el tercero puede evolucionar de forma independiente del
 * contrato con nuestros clientes.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ValidationRequestDto {
    private UUID cuentaId;
    private MovementType tipo;
    private BigDecimal monto;
}
