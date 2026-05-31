package com.fanryan.ledgerflow.common.api;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fanryan.ledgerflow.account.AccountNotFoundException;
import com.fanryan.ledgerflow.account.AccountOwnershipException;
import com.fanryan.ledgerflow.account.InvalidAccountStateException;
import com.fanryan.ledgerflow.account.InvalidAccountRequestException;
import com.fanryan.ledgerflow.auth.InvalidCredentialsException;
import com.fanryan.ledgerflow.auth.InvalidTokenException;
import com.fanryan.ledgerflow.transaction.CurrencyMismatchException;
import com.fanryan.ledgerflow.transaction.ExpiredIdempotencyKeyException;
import com.fanryan.ledgerflow.transaction.IdempotencyConflictException;
import com.fanryan.ledgerflow.transaction.InvalidTransactionRequestException;
import com.fanryan.ledgerflow.transaction.InvalidReversalRequestException;
import com.fanryan.ledgerflow.transaction.InsufficientFundsException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    @ExceptionHandler(InvalidCredentialsException.class)
    public ErrorResponse handleInvalidCredentials(
            InvalidCredentialsException exception,
            WebRequest request
    ) {
        return new ErrorResponse(
                "INVALID_CREDENTIALS",
                exception.getMessage(),
                UUID.randomUUID().toString(),
                OffsetDateTime.now()
        );
    }

    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    @ExceptionHandler(InvalidTokenException.class)
    public ErrorResponse handleInvalidToken(
            InvalidTokenException exception,
            WebRequest request
    ) {
        return new ErrorResponse(
                "INVALID_TOKEN",
                exception.getMessage(),
                UUID.randomUUID().toString(),
                OffsetDateTime.now()
        );
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(InvalidAccountRequestException.class)
    public ErrorResponse handleInvalidAccountRequest(
            InvalidAccountRequestException exception,
            WebRequest request
    ) {
        return new ErrorResponse(
                "INVALID_ACCOUNT_REQUEST",
                exception.getMessage(),
                UUID.randomUUID().toString(),
                OffsetDateTime.now()
        );
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(InvalidTransactionRequestException.class)
    public ErrorResponse handleInvalidTransactionRequest(
            InvalidTransactionRequestException exception,
            WebRequest request
    ) {
        return new ErrorResponse(
                "INVALID_TRANSACTION_REQUEST",
                exception.getMessage(),
                UUID.randomUUID().toString(),
                OffsetDateTime.now()
        );
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(InvalidReversalRequestException.class)
    public ErrorResponse handleInvalidReversalRequest(
            InvalidReversalRequestException exception,
            WebRequest request
    ) {
        return new ErrorResponse(
                "INVALID_REVERSAL_REQUEST",
                exception.getMessage(),
                UUID.randomUUID().toString(),
                OffsetDateTime.now()
        );
    }

    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    @ExceptionHandler(CurrencyMismatchException.class)
    public ErrorResponse handleCurrencyMismatch(
            CurrencyMismatchException exception,
            WebRequest request
    ) {
        return new ErrorResponse(
                "CURRENCY_MISMATCH",
                exception.getMessage(),
                UUID.randomUUID().toString(),
                OffsetDateTime.now()
        );
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    @ExceptionHandler(IdempotencyConflictException.class)
    public ErrorResponse handleIdempotencyConflict(
            IdempotencyConflictException exception,
            WebRequest request
    ) {
        return new ErrorResponse(
                "IDEMPOTENCY_CONFLICT",
                exception.getMessage(),
                UUID.randomUUID().toString(),
                OffsetDateTime.now()
        );
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(ExpiredIdempotencyKeyException.class)
    public ErrorResponse handleExpiredIdempotencyKey(
            ExpiredIdempotencyKeyException exception,
            WebRequest request
    ) {
        return new ErrorResponse(
                "EXPIRED_IDEMPOTENCY_KEY",
                exception.getMessage(),
                UUID.randomUUID().toString(),
                OffsetDateTime.now()
        );
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    @ExceptionHandler(AccountNotFoundException.class)
    public ErrorResponse handleAccountNotFound(
            AccountNotFoundException exception,
            WebRequest request
    ) {
        return new ErrorResponse(
                "ACCOUNT_NOT_FOUND",
                exception.getMessage(),
                UUID.randomUUID().toString(),
                OffsetDateTime.now()
        );
    }

    @ResponseStatus(HttpStatus.FORBIDDEN)
    @ExceptionHandler(AccountOwnershipException.class)
    public ErrorResponse handleAccountOwnership(
            AccountOwnershipException exception,
            WebRequest request
    ) {
        return new ErrorResponse(
                "ACCOUNT_FORBIDDEN",
                exception.getMessage(),
                UUID.randomUUID().toString(),
                OffsetDateTime.now()
        );
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    @ExceptionHandler(InvalidAccountStateException.class)
    public ErrorResponse handleInvalidAccountState(
            InvalidAccountStateException exception,
            WebRequest request
    ) {
        return new ErrorResponse(
                "INVALID_ACCOUNT_STATE",
                exception.getMessage(),
                UUID.randomUUID().toString(),
                OffsetDateTime.now()
        );
    }

    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    @ExceptionHandler(InsufficientFundsException.class)
    public ErrorResponse handleInsufficientFunds(
            InsufficientFundsException exception,
            WebRequest request
    ) {
        return new ErrorResponse(
                "INSUFFICIENT_FUNDS",
                exception.getMessage(),
                UUID.randomUUID().toString(),
                OffsetDateTime.now()
        );
    }
}
