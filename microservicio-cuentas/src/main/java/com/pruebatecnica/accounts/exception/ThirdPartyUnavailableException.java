package com.pruebatecnica.accounts.exception;

/**
 * El servicio externo de validacion no pudo ser contactado (timeout,
 * conexion rechazada, 5xx sostenido) incluso despues de los reintentos
 * configurados, o el circuit breaker esta abierto y rechaza la llamada de
 * forma inmediata. Decision de negocio: en ese caso el movimiento se
 * rechaza (fail-closed) en vez de aplicarse sin validar o encolarse; ver
 * README (Tarea 3) para la justificacion.
 */
public class ThirdPartyUnavailableException extends RuntimeException {

    public ThirdPartyUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
