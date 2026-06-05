package com.fanryan.ledgerflow.paycore;

import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fanryan.ledgerflow.deadletter.DeadLetterEventRepository;
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

    private static final String PAYMENT_CAPTURED_TOPIC = "payment.captured";
    private static final String PAYMENT_SETTLED_TOPIC = "payment.settled";

    private final ObjectMapper objectMapper;
    private final TransactionService transactionService;
    private final DeadLetterEventRepository deadLetterEventRepository;

    public PayCoreConsumer(
            ObjectMapper objectMapper,
            TransactionService transactionService,
            DeadLetterEventRepository deadLetterEventRepository
    ) {
        this.objectMapper = objectMapper;
        this.transactionService = transactionService;
        this.deadLetterEventRepository = deadLetterEventRepository;
    }

    @KafkaListener(
            topics = PAYMENT_CAPTURED_TOPIC,
            groupId = "${paycore.kafka.consumer-group-id:paycore-consumer}"
    )
    public void consumePaymentCaptured(String payload) {
        try {
            PayCorePaymentCapturedPayload event = parse(payload, PayCorePaymentCapturedPayload.class);

            validatePaymentCaptured(event);

            submitDeposit(
                    event.ownerUserId(),
                    event.eventId(),
                    event.merchantAccountId(),
                    event.amountMinor(),
                    event.currency(),
                    "PayCore payment captured: " + event.paymentId()
            );

            log.info(
                    "Posted PayCore payment.captured eventId={} paymentId={} merchantAccountId={} amountMinor={} currency={}",
                    event.eventId(),
                    event.paymentId(),
                    event.merchantAccountId(),
                    event.amountMinor(),
                    event.currency()
            );
        } catch (Exception exception) {
            saveDeadLetter(PAYMENT_CAPTURED_TOPIC, payload, exception);
        }
    }

    @KafkaListener(
            topics = PAYMENT_SETTLED_TOPIC,
            groupId = "${paycore.kafka.consumer-group-id:paycore-consumer}"
    )
    public void consumePaymentSettled(String payload) {
        try {
            PayCorePaymentSettledPayload event = parse(payload, PayCorePaymentSettledPayload.class);

            validatePaymentSettled(event);

            submitDeposit(
                    event.ownerUserId(),
                    event.eventId(),
                    event.merchantAccountId(),
                    event.amountMinor(),
                    event.currency(),
                    "PayCore payment settled: " + event.paymentId()
            );

            log.info(
                    "Posted PayCore payment.settled eventId={} paymentId={} merchantAccountId={} amountMinor={} currency={}",
                    event.eventId(),
                    event.paymentId(),
                    event.merchantAccountId(),
                    event.amountMinor(),
                    event.currency()
            );
        } catch (Exception exception) {
            saveDeadLetter(PAYMENT_SETTLED_TOPIC, payload, exception);
        }
    }

    private void submitDeposit(
            String ownerUserId,
            String eventId,
            String merchantAccountId,
            long amountMinor,
            String currency,
            String description
    ) {
        transactionService.submitTransaction(
                UUID.fromString(ownerUserId),
                eventId,
                new CreateTransactionRequest(
                        UUID.fromString(merchantAccountId),
                        TransactionType.DEPOSIT,
                        amountMinor,
                        currency,
                        description
                )
        );
    }

    private <T> T parse(String payload, Class<T> eventType) {
        try {
            return objectMapper.readValue(payload, eventType);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid PayCore event payload", exception);
        }
    }

    private void validatePaymentCaptured(PayCorePaymentCapturedPayload event) {
        validateCommonFields(
                event.eventId(),
                event.paymentId(),
                event.ownerUserId(),
                event.merchantAccountId(),
                event.amountMinor(),
                event.currency()
        );
    }

    private void validatePaymentSettled(PayCorePaymentSettledPayload event) {
        validateCommonFields(
                event.eventId(),
                event.paymentId(),
                event.ownerUserId(),
                event.merchantAccountId(),
                event.amountMinor(),
                event.currency()
        );
    }

    private void validateCommonFields(
            String eventId,
            String paymentId,
            String ownerUserId,
            String merchantAccountId,
            long amountMinor,
            String currency
    ) {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("PayCore event id is required");
        }

        if (paymentId == null || paymentId.isBlank()) {
            throw new IllegalArgumentException("PayCore payment id is required");
        }

        if (ownerUserId == null || ownerUserId.isBlank()) {
            throw new IllegalArgumentException("PayCore owner user id is required");
        }

        if (merchantAccountId == null || merchantAccountId.isBlank()) {
            throw new IllegalArgumentException("PayCore merchant account id is required");
        }

        if (amountMinor <= 0) {
            throw new IllegalArgumentException("PayCore amount must be greater than zero");
        }

        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("PayCore currency must be a 3-letter uppercase code");
        }
    }

    private void saveDeadLetter(
            String sourceTopic,
            String payload,
            Exception exception
    ) {
        deadLetterEventRepository.save(
                sourceTopic,
                null,
                payload,
                exception.getMessage()
        );

        log.warn(
                "Saved PayCore event to dead letter topic={} error={}",
                sourceTopic,
                exception.getMessage()
        );
    }
}