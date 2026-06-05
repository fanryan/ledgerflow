package com.fanryan.ledgerflow.paycore;

import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fanryan.ledgerflow.transaction.CreateTransactionRequest;
import com.fanryan.ledgerflow.transaction.TransactionService;
import com.fanryan.ledgerflow.transaction.TransactionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class PayCoreConsumer {

    private static final Logger log = LoggerFactory.getLogger(PayCoreConsumer.class);

    private final ObjectMapper objectMapper;
    private final TransactionService transactionService;

    public PayCoreConsumer(
            ObjectMapper objectMapper,
            TransactionService transactionService
    ) {
        this.objectMapper = objectMapper;
        this.transactionService = transactionService;
    }

    @KafkaListener(
            topics = "payment.captured",
            groupId = "${paycore.kafka.consumer-group-id:paycore-consumer}"
    )
    public void consumePaymentCaptured(String payload) {
        PayCorePaymentCapturedPayload event = parsePaymentCaptured(payload);

        validate(event);

        transactionService.submitTransaction(
                UUID.fromString(event.ownerUserId()),
                event.eventId(),
                new CreateTransactionRequest(
                        UUID.fromString(event.merchantAccountId()),
                        TransactionType.DEPOSIT,
                        event.amountMinor(),
                        event.currency(),
                        "PayCore payment captured: " + event.paymentId()
                )
        );

        log.info(
                "Posted PayCore payment.captured eventId={} paymentId={} merchantAccountId={} amountMinor={} currency={}",
                event.eventId(),
                event.paymentId(),
                event.merchantAccountId(),
                event.amountMinor(),
                event.currency()
        );
    }

    private PayCorePaymentCapturedPayload parsePaymentCaptured(String payload) {
        try {
            return objectMapper.readValue(payload, PayCorePaymentCapturedPayload.class);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid payment.captured payload", exception);
        }
    }

    private void validate(PayCorePaymentCapturedPayload event) {
        if (event.eventId() == null || event.eventId().isBlank()) {
            throw new IllegalArgumentException("PayCore event id is required");
        }

        if (event.paymentId() == null || event.paymentId().isBlank()) {
            throw new IllegalArgumentException("PayCore payment id is required");
        }

        if (event.ownerUserId() == null || event.ownerUserId().isBlank()) {
            throw new IllegalArgumentException("PayCore owner user id is required");
        }

        if (event.merchantAccountId() == null || event.merchantAccountId().isBlank()) {
            throw new IllegalArgumentException("PayCore merchant account id is required");
        }

        if (event.amountMinor() <= 0) {
            throw new IllegalArgumentException("PayCore amount must be greater than zero");
        }

        if (event.currency() == null || !event.currency().matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("PayCore currency must be a 3-letter uppercase code");
        }
    }
}