package com.pruebatecnica.accounts.controller;

import com.pruebatecnica.accounts.dto.AccountResponse;
import com.pruebatecnica.accounts.dto.CreateAccountRequest;
import com.pruebatecnica.accounts.dto.CreateTransactionRequest;
import com.pruebatecnica.accounts.dto.ErrorResponse;
import com.pruebatecnica.accounts.dto.TransactionResponse;
import com.pruebatecnica.accounts.service.AccountService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/accounts")
@RequiredArgsConstructor
@Tag(name = "Cuentas", description = "Alta de cuentas y registro de movimientos")
public class AccountController {

    private final AccountService accountService;

    @PostMapping
    public ResponseEntity<AccountResponse> createAccount(@Valid @RequestBody CreateAccountRequest request) {
        AccountResponse response = accountService.createAccount(request);
        return ResponseEntity.created(URI.create("/accounts/" + response.getId())).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<AccountResponse> getAccount(@PathVariable UUID id) {
        return ResponseEntity.ok(accountService.getAccount(id));
    }

}
