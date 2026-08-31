package com.pruebatecnica.accounts.service;

import com.pruebatecnica.accounts.adapter.validation.ValidationClient;
import com.pruebatecnica.accounts.adapter.validation.ValidationRequestDto;
import com.pruebatecnica.accounts.adapter.validation.ValidationResult;
import com.pruebatecnica.accounts.dto.CreateAccountRequest;
import com.pruebatecnica.accounts.dto.CreateTransactionRequest;
import com.pruebatecnica.accounts.dto.TransactionResponse;
import com.pruebatecnica.accounts.exception.AccountNotFoundException;
import com.pruebatecnica.accounts.exception.InsufficientBalanceException;
import com.pruebatecnica.accounts.exception.MovementRejectedException;
import com.pruebatecnica.accounts.exception.ThirdPartyUnavailableException;
import com.pruebatecnica.accounts.model.Account;
import com.pruebatecnica.accounts.model.Movement;
import com.pruebatecnica.accounts.model.MovementType;
import com.pruebatecnica.accounts.repository.AccountRepository;
import com.pruebatecnica.accounts.repository.MovementRepository;
import com.pruebatecnica.accounts.service.impl.AccountServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias de la logica de negocio de AccountServiceImpl, con los
 * repositorios y el ValidationClient mockeados (sin contexto de Spring, sin
 * base de datos real). Cubren exactamente los casos que se piden:
 * saldo insuficiente, idempotencia, y el comportamiento ante la validacion
 * externa (aprobada / rechazada / tercero caido).
 */
@ExtendWith(MockitoExtension.class)
class AccountServiceImplTest {

    @Mock
    private AccountRepository accountRepository;
    @Mock
    private MovementRepository movementRepository;
    @Mock
    private ValidationClient validationClient;

    private AccountServiceImpl service;

    private UUID accountId;
    private Account account;

    @BeforeEach
    void setUp() {
        service = new AccountServiceImpl(accountRepository, movementRepository, validationClient);
        accountId = UUID.randomUUID();
        account = Account.builder()
                .id(accountId)
                .titular("Cuenta de prueba")
                .saldo(new BigDecimal("100.00"))
                .fechaCreacion(Instant.now())
                .build();
    }

    @Test
    void createAccount_devuelveCuentaConElSaldoInicialEnviado() {
        CreateAccountRequest request = new CreateAccountRequest("Nuevo Titular", new BigDecimal("50.00"));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = service.createAccount(request);

        assertThat(response.getTitular()).isEqualTo("Nuevo Titular");
        assertThat(response.getSaldo()).isEqualByComparingTo("50.00");
    }

    @Test
    void getAccount_cuentaInexistente_lanzaAccountNotFoundException() {
        when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAccount(accountId))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void registerTransaction_cuentaInexistente_lanzaAccountNotFoundException() {
        when(accountRepository.findWithLockById(accountId)).thenReturn(Optional.empty());

        CreateTransactionRequest request = new CreateTransactionRequest(MovementType.DEBIT, new BigDecimal("10.00"));

        assertThatThrownBy(() -> service.registerTransaction(accountId, request, null))
                .isInstanceOf(AccountNotFoundException.class);

        verifyNoInteractions(validationClient);
        verify(movementRepository, never()).save(any());
    }

    @Test
    void registerTransaction_debitoExcedeSaldo_lanzaInsufficientBalance_yNuncaLlamaAlValidadorExterno() {
        when(accountRepository.findWithLockById(accountId)).thenReturn(Optional.of(account));

        CreateTransactionRequest request = new CreateTransactionRequest(MovementType.DEBIT, new BigDecimal("999.00"));

        assertThatThrownBy(() -> service.registerTransaction(accountId, request, null))
                .isInstanceOf(InsufficientBalanceException.class);

        // Regla local (barata) se evalua antes de gastar una llamada de red:
        // si el saldo ya es insuficiente, el validador externo ni se llama.
        verifyNoInteractions(validationClient);
        verify(accountRepository, never()).save(any());
        verify(movementRepository, never()).save(any());
    }

    @Test
    void registerTransaction_debitoExactoAlSaldoDisponible_permitidoYResultaEnSaldoCero() {
        when(accountRepository.findWithLockById(accountId)).thenReturn(Optional.of(account));
        when(validationClient.validate(any())).thenReturn(new ValidationResult(true, "OK"));
        when(movementRepository.save(any(Movement.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateTransactionRequest request = new CreateTransactionRequest(MovementType.DEBIT, new BigDecimal("100.00"));
        TransactionResponse response = service.registerTransaction(accountId, request, null);

        assertThat(response.getSaldoResultante()).isEqualByComparingTo("0.00");
        assertThat(account.getSaldo()).isEqualByComparingTo("0.00");
    }

    @Test
    void registerTransaction_creditoAprobado_actualizaSaldoYGuardaMovimiento() {
        when(accountRepository.findWithLockById(accountId)).thenReturn(Optional.of(account));
        when(validationClient.validate(any())).thenReturn(new ValidationResult(true, "OK"));
        when(movementRepository.save(any(Movement.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateTransactionRequest request = new CreateTransactionRequest(MovementType.CREDIT, new BigDecimal("25.00"));
        TransactionResponse response = service.registerTransaction(accountId, request, null);

        assertThat(response.isReplay()).isFalse();
        assertThat(response.getSaldoResultante()).isEqualByComparingTo("125.00");
        verify(accountRepository).save(account);

        ArgumentCaptor<Movement> movementCaptor = ArgumentCaptor.forClass(Movement.class);
        verify(movementRepository).save(movementCaptor.capture());
        assertThat(movementCaptor.getValue().getTipo()).isEqualTo(MovementType.CREDIT);
        assertThat(movementCaptor.getValue().getSaldoResultante()).isEqualByComparingTo("125.00");
    }

    @Test
    void registerTransaction_idempotencia_mismaClaveDevuelveElMovimientoExistente_sinDuplicarNiLlamarAlValidador() {
        String idempotencyKey = "clave-repetida";
        Movement movimientoExistente = Movement.builder()
                .id(UUID.randomUUID())
                .cuentaId(accountId)
                .tipo(MovementType.DEBIT)
                .monto(new BigDecimal("30.00"))
                .saldoResultante(new BigDecimal("70.00"))
                .idempotencyKey(idempotencyKey)
                .fecha(Instant.now())
                .build();

        when(accountRepository.findWithLockById(accountId)).thenReturn(Optional.of(account));
        when(movementRepository.findByCuentaIdAndIdempotencyKey(accountId, idempotencyKey))
                .thenReturn(Optional.of(movimientoExistente));

        CreateTransactionRequest request = new CreateTransactionRequest(MovementType.DEBIT, new BigDecimal("30.00"));
        TransactionResponse response = service.registerTransaction(accountId, request, idempotencyKey);

        assertThat(response.isReplay()).isTrue();
        assertThat(response.getId()).isEqualTo(movimientoExistente.getId());
        // Ni se vuelve a llamar al validador externo ni se persiste nada de nuevo.
        verifyNoInteractions(validationClient);
        verify(accountRepository, never()).save(any());
        verify(movementRepository, never()).save(any());
    }

    @Test
    void registerTransaction_idempotencyKeyEnBlanco_seTrataComoSiNoSeHubieraEnviado() {
        when(accountRepository.findWithLockById(accountId)).thenReturn(Optional.of(account));
        when(validationClient.validate(any())).thenReturn(new ValidationResult(true, "OK"));
        when(movementRepository.save(any(Movement.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateTransactionRequest request = new CreateTransactionRequest(MovementType.CREDIT, new BigDecimal("10.00"));
        service.registerTransaction(accountId, request, "   ");

        verify(movementRepository, never()).findByCuentaIdAndIdempotencyKey(any(), any());
        ArgumentCaptor<Movement> movementCaptor = ArgumentCaptor.forClass(Movement.class);
        verify(movementRepository).save(movementCaptor.capture());
        assertThat(movementCaptor.getValue().getIdempotencyKey()).isNull();
    }

    @Test
    void registerTransaction_rechazadoPorElValidadorExterno_lanzaMovementRejected_yNoAplicaElMovimiento() {
        when(accountRepository.findWithLockById(accountId)).thenReturn(Optional.of(account));
        when(validationClient.validate(any(ValidationRequestDto.class)))
                .thenReturn(new ValidationResult(false, "Monto senalado para revision manual"));

        CreateTransactionRequest request = new CreateTransactionRequest(MovementType.DEBIT, new BigDecimal("10.00"));

        assertThatThrownBy(() -> service.registerTransaction(accountId, request, null))
                .isInstanceOf(MovementRejectedException.class)
                .hasMessageContaining("Monto senalado para revision manual");

        assertThat(account.getSaldo()).isEqualByComparingTo("100.00");
        verify(accountRepository, never()).save(any());
        verify(movementRepository, never()).save(any());
    }

    @Test
    void registerTransaction_terceroCaido_propagaThirdPartyUnavailable_yNoAplicaElMovimiento() {
        when(accountRepository.findWithLockById(accountId)).thenReturn(Optional.of(account));
        when(validationClient.validate(any(ValidationRequestDto.class)))
                .thenThrow(new ThirdPartyUnavailableException("Servicio de validacion no disponible",
                        new RuntimeException("timeout")));

        CreateTransactionRequest request = new CreateTransactionRequest(MovementType.DEBIT, new BigDecimal("10.00"));

        assertThatThrownBy(() -> service.registerTransaction(accountId, request, null))
                .isInstanceOf(ThirdPartyUnavailableException.class);

        assertThat(account.getSaldo()).isEqualByComparingTo("100.00");
        verify(accountRepository, never()).save(any());
        verify(movementRepository, never()).save(any());
    }
}
