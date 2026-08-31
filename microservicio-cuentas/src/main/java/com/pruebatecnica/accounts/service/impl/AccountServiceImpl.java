package com.pruebatecnica.accounts.service.impl;

import com.pruebatecnica.accounts.adapter.validation.ValidationClient;
import com.pruebatecnica.accounts.adapter.validation.ValidationRequestDto;
import com.pruebatecnica.accounts.adapter.validation.ValidationResult;
import com.pruebatecnica.accounts.dto.AccountResponse;
import com.pruebatecnica.accounts.dto.CreateAccountRequest;
import com.pruebatecnica.accounts.dto.CreateTransactionRequest;
import com.pruebatecnica.accounts.dto.TransactionResponse;
import com.pruebatecnica.accounts.exception.AccountNotFoundException;
import com.pruebatecnica.accounts.exception.InsufficientBalanceException;
import com.pruebatecnica.accounts.exception.MovementRejectedException;
import com.pruebatecnica.accounts.model.Account;
import com.pruebatecnica.accounts.model.Movement;
import com.pruebatecnica.accounts.model.MovementType;
import com.pruebatecnica.accounts.repository.AccountRepository;
import com.pruebatecnica.accounts.repository.MovementRepository;
import com.pruebatecnica.accounts.service.AccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AccountServiceImpl implements AccountService {

    private final AccountRepository accountRepository;
    private final MovementRepository movementRepository;
    private final ValidationClient validationClient;

    @Override
    @Transactional
    public AccountResponse createAccount(CreateAccountRequest request) {
        Account account = Account.builder()
                .titular(request.getTitular())
                .saldo(request.getSaldoInicial())
                .build();
        Account saved = accountRepository.save(account);
        return toAccountResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public AccountResponse getAccount(UUID accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        return toAccountResponse(account);
    }

    @Override
    @Transactional
    public TransactionResponse registerTransaction(UUID accountId, CreateTransactionRequest request,
            String idempotencyKeyHeader) {
        String idempotencyKey = StringUtils.hasText(idempotencyKeyHeader) ? idempotencyKeyHeader.trim() : null;

        // Bloqueo pesimista: serializa cualquier otra transaccion concurrente
        // sobre la misma cuenta (incluyendo un reintento con la misma
        // Idempotency-Key), evitando lost updates y garantizando que la
        // comprobacion de idempotencia hecha a continuacion sea segura.
        Account account = accountRepository.findWithLockById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));

        if (idempotencyKey != null) {
            Optional<Movement> existing = movementRepository.findByCuentaIdAndIdempotencyKey(accountId, idempotencyKey);
            if (existing.isPresent()) {
                return toTransactionResponse(existing.get(), true);
            }
        }

        // Regla local (barata) primero: si el saldo ya es insuficiente, se
        // rechaza sin gastar una llamada de red al validador externo.
        BigDecimal nuevoSaldo = calcularNuevoSaldo(account, request);

        // Antes de confirmar el movimiento, se consulta al validador
        // externo (Tarea 3). Si el tercero esta caido, ValidationClient
        // lanza ThirdPartyUnavailableException (via el fallback de
        // Resilience4j) y la transaccion se revierte sin aplicar el
        // movimiento (fail-closed, ver README).
        ValidationResult validacion = validationClient
                .validate(new ValidationRequestDto(accountId, request.getTipo(), request.getMonto()));

        if (!validacion.isAprobado()) {
            throw new MovementRejectedException(accountId, validacion.getMotivo());
        }

        account.setSaldo(nuevoSaldo);
        accountRepository.save(account);

        Movement movement = Movement.builder()
                .cuentaId(accountId)
                .tipo(request.getTipo())
                .monto(request.getMonto())
                .saldoResultante(nuevoSaldo)
                .idempotencyKey(idempotencyKey)
                .build();
        Movement saved = movementRepository.save(movement);

        return toTransactionResponse(saved, false);
    }

    private BigDecimal calcularNuevoSaldo(Account account, CreateTransactionRequest request) {
        BigDecimal monto = request.getMonto();
        if (request.getTipo() == MovementType.DEBIT) {
            if (account.getSaldo().compareTo(monto) < 0) {
                throw new InsufficientBalanceException(account.getId(), account.getSaldo(), monto);
            }
            return account.getSaldo().subtract(monto);
        }
        return account.getSaldo().add(monto);
    }

    private AccountResponse toAccountResponse(Account account) {
        return AccountResponse.builder()
                .id(account.getId())
                .titular(account.getTitular())
                .saldo(account.getSaldo())
                .fechaCreacion(account.getFechaCreacion())
                .build();
    }

    private TransactionResponse toTransactionResponse(Movement movement, boolean replay) {
        return TransactionResponse.builder()
                .id(movement.getId())
                .cuentaId(movement.getCuentaId())
                .tipo(movement.getTipo())
                .monto(movement.getMonto())
                .saldoResultante(movement.getSaldoResultante())
                .fecha(movement.getFecha())
                .idempotencyKey(movement.getIdempotencyKey())
                .replay(replay)
                .build();
    }
}
