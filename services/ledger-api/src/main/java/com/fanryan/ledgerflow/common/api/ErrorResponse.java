package com.fanryan.ledgerflow.common.api;

import java.time.OffsetDateTime;

public record ErrorResponse(
        String errorCode,
        String message,
        String requestId,
        OffsetDateTime timestamp
) {
}
