package com.pruebatecnica.accounts.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Entidad Movimiento. Guarda el saldo resultante (running balance) en el
 * momento del movimiento para que el estado de cuenta (Tarea 2) no dependa
 * de recalcular sumas historicas en cada lectura.
 */
@Entity
@Table(name = "movimiento", indexes = {
        @Index(name = "ix_movimiento_cuenta", columnList = "cuenta_id"),
        @Index(name = "ix_movimiento_idempotency", columnList = "cuenta_id, idempotency_key")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Movement {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "cuenta_id", nullable = false, updatable = false)
    private UUID cuentaId;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo", nullable = false, length = 10, updatable = false)
    private MovementType tipo;

    @Column(name = "monto", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal monto;

    @Column(name = "saldo_resultante", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal saldoResultante;

    /**
     * Clave de idempotencia enviada por el cliente (cabecera Idempotency-Key).
     * Nula si el cliente no la envio. La unicidad fuerte por (cuenta_id,
     * idempotency_key)
     * cuando no es nula se refuerza a nivel de base de datos en Tarea 2 mediante un
     * indice unico filtrado (SQL Server no permite UNIQUE simple con multiples NULL
     * de forma directamente portable, por eso se documenta como filtered index).
     */
    @Column(name = "idempotency_key", length = 100, updatable = false)
    private String idempotencyKey;

    @Column(name = "fecha", nullable = false, updatable = false)
    private Instant fecha;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (fecha == null) {
            fecha = Instant.now();
        }
    }
}
