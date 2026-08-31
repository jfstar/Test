package com.pruebatecnica.accounts.dto;

import com.pruebatecnica.accounts.model.MovementType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas unitarias de las restricciones Bean Validation de los DTOs de
 * entrada. No requieren contexto de Spring: se instancia el Validator
 * directamente, igual que hace Spring internamente al aplicar @Valid.
 */
class RequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    void createAccountRequest_valido_sinViolaciones() {
        var request = new CreateAccountRequest("Maria Perez", new BigDecimal("100.00"));
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void createAccountRequest_titularEnBlanco_violaNotBlank() {
        var request = new CreateAccountRequest("   ", new BigDecimal("100.00"));
        Set<ConstraintViolation<CreateAccountRequest>> violaciones = validator.validate(request);
        assertThat(violaciones).extracting(v -> v.getPropertyPath().toString())
                .contains("titular");
    }

    @Test
    void createAccountRequest_saldoInicialNegativo_violaDecimalMin() {
        var request = new CreateAccountRequest("Maria Perez", new BigDecimal("-0.01"));
        Set<ConstraintViolation<CreateAccountRequest>> violaciones = validator.validate(request);
        assertThat(violaciones).extracting(v -> v.getPropertyPath().toString())
                .contains("saldoInicial");
    }

    @Test
    void createAccountRequest_saldoInicialCero_esValido() {
        var request = new CreateAccountRequest("Maria Perez", BigDecimal.ZERO);
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void createTransactionRequest_valido_sinViolaciones() {
        var request = new CreateTransactionRequest(MovementType.DEBIT, new BigDecimal("10.00"));
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void createTransactionRequest_tipoNulo_violaNotNull() {
        var request = new CreateTransactionRequest(null, new BigDecimal("10.00"));
        Set<ConstraintViolation<CreateTransactionRequest>> violaciones = validator.validate(request);
        assertThat(violaciones).extracting(v -> v.getPropertyPath().toString())
                .contains("tipo");
    }

    @Test
    void createTransactionRequest_montoCero_violaDecimalMin() {
        var request = new CreateTransactionRequest(MovementType.CREDIT, BigDecimal.ZERO);
        Set<ConstraintViolation<CreateTransactionRequest>> violaciones = validator.validate(request);
        assertThat(violaciones).extracting(v -> v.getPropertyPath().toString())
                .contains("monto");
    }

    @Test
    void createTransactionRequest_montoNegativo_violaDecimalMin() {
        var request = new CreateTransactionRequest(MovementType.CREDIT, new BigDecimal("-5.00"));
        assertThat(validator.validate(request)).isNotEmpty();
    }
}
