package com.fanryan.ledgerflow.deadletter;

public record DeadLetterReplayResponse(
        int replayed
) {
}