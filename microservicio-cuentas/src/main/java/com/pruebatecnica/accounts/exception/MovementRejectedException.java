package com.pruebatecnica.accounts.exception;

import java.util.UUID;

/**
 * El servicio externo de validacion respondio y rechazo el movimiento
 * (decision de negocio del tercero, no una falla de comunicacion). Ver
 * ThirdPartyUnavailableException para el caso de tercero caido.
 */
public class MovementRejectedException extends RuntimeException {

    public MovementRejectedException(UUID accountId, String motivo) {
        super("El movimiento sobre la cuenta " + accountId
                + " fue rechazado por el servicio de validacion: " + motivo);
    }
}