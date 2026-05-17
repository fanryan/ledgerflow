package com.fanryan.ledgerflow.common.api;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fanryan.ledgerflow.auth.InvalidCredentialsException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.annotation.ResponseStatus;
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
}