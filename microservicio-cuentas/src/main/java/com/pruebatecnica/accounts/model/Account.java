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
 * Entidad Cuenta. El id es UUID (no correlativo) para evitar enumeracion de
 * cuentas desde el API publico.
 */
@Entity
@Table(name = "cuenta")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Account {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "titular", nullable = false, length = 150)
    private String titular;

    @Column(name = "saldo", nullable = false, precision = 19, scale = 4)
    private BigDecimal saldo;

    @Column(name = "fecha_creacion", nullable = false, updatable = false)
    private Instant fechaCreacion;

    /**
     * Control de concurrencia optimista como segunda linea de defensa,
     * ademas del bloqueo pesimista aplicado explicitamente al debitar/acreditar
     * (ver AccountRepository#findWithLockById).
     */
    @Version
    @Column(name = "version")
    private Long version;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (fechaCreacion == null) {
            fechaCreacion = Instant.now();
        }
    }
}
