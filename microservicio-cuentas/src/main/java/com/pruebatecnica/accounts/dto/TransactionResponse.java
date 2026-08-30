package com.pruebatecnica.accounts.dto;

import com.pruebatecnica.accounts.model.MovementType;
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
@Schema(description = "Resultado de un movimiento registrado")
public class TransactionResponse {

    private UUID id;
    private UUID cuentaId;
    private MovementType tipo;
    private BigDecimal monto;
    private BigDecimal saldoResultante;
    private Instant fecha;
    private String idempotencyKey;

    @Schema(description = "true si esta respuesta corresponde a un movimiento ya existente devuelto por una repeticion con la misma Idempotency-Key")
    private boolean replay;
}
