package com.pruebatecnica.accounts.adapter.validation;

/**
 * Puerto hacia el servicio externo de validacion. El controller y el
 * service dependen solo de esta interfaz, nunca de RestClient/WebClient
 * directamente: la integracion HTTP y la resiliencia quedan aisladas en el
 * adaptador (RestValidationClient).
 */
public interface ValidationClient {

    /**
     * Puede lanzar ThirdPartyUnavailableException
     * (com.pruebatecnica.accounts.exception)
     * si el tercero no responde ni siquiera despues de reintentos, o si el
     * circuit breaker esta abierto.
     */
    ValidationResult validate(ValidationRequestDto request);
}
