package com.pruebatecnica.accounts.exception;

import java.math.BigDecimal;
import java.util.UUID;

public class InsufficientBalanceException extends RuntimeException {

    public InsufficientBalanceException(UUID accountId, BigDecimal saldoActual, BigDecimal montoSolicitado) {
        super("Saldo insuficiente en cuenta " + accountId + ": saldo=" + saldoActual
                + ", monto solicitado=" + montoSolicitado);
    }
}