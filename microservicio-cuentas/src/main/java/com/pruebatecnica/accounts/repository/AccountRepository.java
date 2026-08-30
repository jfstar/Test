package com.pruebatecnica.accounts.repository;

import com.pruebatecnica.accounts.model.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    /**
     * Bloqueo pesimista de escritura para serializar debitos/creditos
     * concurrentes sobre la misma cuenta y evitar condiciones de carrera que
     * dejen el saldo en un estado incorrecto (lost update).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findWithLockById(@Param("id") UUID id);
}