package com.pruebatecnica.accounts.controller;

import com.pruebatecnica.accounts.dto.AccountResponse;
import com.pruebatecnica.accounts.dto.CreateAccountRequest;
import com.pruebatecnica.accounts.dto.CreateTransactionRequest;
import com.pruebatecnica.accounts.dto.ErrorResponse;
import com.pruebatecnica.accounts.dto.TransactionResponse;
import com.pruebatecnica.accounts.service.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/accounts")
@RequiredArgsConstructor
@Tag(name = "Cuentas", description = "Alta de cuentas y registro de movimientos")
public class AccountController {

    private final AccountService accountService;

    @PostMapping
    @Operation(summary = "Crea una cuenta nueva con saldo inicial >= 0")
    @ApiResponse(responseCode = "201", description = "Cuenta creada")
    @ApiResponse(responseCode = "400", description = "Datos de entrada invalidos", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<AccountResponse> createAccount(@Valid @RequestBody CreateAccountRequest request) {
        AccountResponse response = accountService.createAccount(request);
        return ResponseEntity.created(URI.create("/accounts/" + response.getId())).body(response);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Obtiene una cuenta por id, incluyendo su saldo actual")
    @ApiResponse(responseCode = "200", description = "Cuenta encontrada")
    @ApiResponse(responseCode = "404", description = "Cuenta inexistente", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<AccountResponse> getAccount(@PathVariable UUID id) {
        return ResponseEntity.ok(accountService.getAccount(id));
    }

    @PostMapping("/{id}/transactions")
    @Operation(summary = "Registra un movimiento (CREDIT/DEBIT) sobre la cuenta", description = "Un DEBIT que exceda el saldo disponible se rechaza con 409. "
            + "Antes de confirmar, se consulta a un servicio externo de validacion (Tarea 3, Resilience4j): "
            + "si el tercero rechaza el movimiento responde 422; si el tercero esta caido (circuit breaker abierto "
            + "o reintentos agotados) responde 503 sin aplicar el movimiento. "
            + "Si se envia la cabecera Idempotency-Key y ya existe un movimiento con esa "
            + "misma clave para la cuenta, se devuelve el movimiento original (200) sin duplicarlo.")
    @ApiResponse(responseCode = "201", description = "Movimiento registrado")
    @ApiResponse(responseCode = "200", description = "Movimiento ya existente devuelto por idempotencia (reintento)")
    @ApiResponse(responseCode = "400", description = "Datos de entrada invalidos", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "Cuenta inexistente", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Saldo insuficiente para el debito solicitado", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "422", description = "El servicio externo de validacion rechazo el movimiento", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "503", description = "El servicio externo de validacion no esta disponible (circuit breaker abierto o reintentos agotados)", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<TransactionResponse> createTransaction(
            @PathVariable UUID id,
            @Valid @RequestBody CreateTransactionRequest request,
            @Parameter(description = "Clave de idempotencia provista por el cliente") @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        TransactionResponse response = accountService.registerTransaction(id, request, idempotencyKey);

        if (response.isReplay()) {
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(response);
    }
}
