package com.pruebatecnica.accounts.service;

import com.pruebatecnica.accounts.dto.AccountResponse;
import com.pruebatecnica.accounts.dto.CreateAccountRequest;
import com.pruebatecnica.accounts.dto.CreateTransactionRequest;
import com.pruebatecnica.accounts.dto.TransactionResponse;

import java.util.UUID;

public interface AccountService {

    AccountResponse createAccount(CreateAccountRequest request);

    AccountResponse getAccount(UUID accountId);

    TransactionResponse registerTransaction(UUID accountId, CreateTransactionRequest request, String idempotencyKey);
}