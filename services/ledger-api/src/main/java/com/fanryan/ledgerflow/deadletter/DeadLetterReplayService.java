package com.fanryan.ledgerflow.deadletter;

import com.fanryan.ledgerflow.paycore.PayCoreConsumer;
import org.springframework.stereotype.Service;

@Service
public class DeadLetterReplayService {

    private static final String PAYMENT_CAPTURED_TOPIC = "payment.captured";
    private static final String PAYMENT_SETTLED_TOPIC = "payment.settled";

    private final DeadLetterEventRepository deadLetterEventRepository;
    private final PayCoreConsumer payCoreConsumer;

    public DeadLetterReplayService(
            DeadLetterEventRepository deadLetterEventRepository,
            PayCoreConsumer payCoreConsumer
    ) {
        this.deadLetterEventRepository = deadLetterEventRepository;
        this.payCoreConsumer = payCoreConsumer;
    }

    public int replayPending(int limit) {
        int replayed = 0;

        for (DeadLetterEvent event : deadLetterEventRepository.findPending(limit)) {
            replay(event);
            replayed++;
        }

        return replayed;
    }

    private void replay(DeadLetterEvent event) {
        if (PAYMENT_CAPTURED_TOPIC.equals(event.sourceTopic())) {
            payCoreConsumer.consumePaymentCaptured(event.payload());
            deadLetterEventRepository.markReplayed(event.id());
            return;
        }

        if (PAYMENT_SETTLED_TOPIC.equals(event.sourceTopic())) {
            payCoreConsumer.consumePaymentSettled(event.payload());
            deadLetterEventRepository.markReplayed(event.id());
            return;
        }

        throw new IllegalArgumentException("Unsupported dead letter source topic: " + event.sourceTopic());
    }
}