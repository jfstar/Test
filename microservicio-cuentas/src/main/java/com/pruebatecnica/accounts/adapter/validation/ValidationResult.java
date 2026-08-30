package com.pruebatecnica.accounts.adapter.validation;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Resultado de una validacion exitosa (el tercero SI respondio, con
 * "aprobado=false" o "aprobado=true"). Esto es distinto de que el tercero
 * este caido: esta clase nunca representa una falla de comunicacion, solo
 * una decision de negocio del validador externo.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ValidationResult {
    private boolean aprobado;
    private String motivo;
}
