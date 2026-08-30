package com.pruebatecnica.accounts.adapter.validation;

/**
 * Falla de comunicacion con el servicio externo de validacion (timeout,
 * conexion rechazada, 5xx, respuesta no parseable). Es la UNICA excepcion
 * que Resilience4j reintenta y contabiliza para el circuit breaker
 * (ver application.yml, resilience4j.retry.instances.validationService).
 *
 * Nunca escapa fuera de la capa adapter: RestValidationClient#fallback la
 * traduce siempre a ThirdPartyUnavailableException antes de que llegue al
 * service/controller.
 */
public class ValidationServiceException extends RuntimeException {

    public ValidationServiceException(String message) {
        super(message);
    }

    public ValidationServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
