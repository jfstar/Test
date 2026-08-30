package com.pruebatecnica.accounts.repository;

import com.pruebatecnica.accounts.model.Movement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface MovementRepository extends JpaRepository<Movement, UUID> {

    Optional<Movement> findByCuentaIdAndIdempotencyKey(UUID cuentaId, String idempotencyKey);
}
