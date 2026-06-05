package com.fanryan.ledgerflow.deadletter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fanryan.ledgerflow.paycore.PayCoreConsumer;
import org.junit.jupiter.api.Test;

class DeadLetterReplayServiceTest {

    @Test
    void replayPendingReplaysPayCoreCapturedEventAndMarksReplayed() {
        DeadLetterEventRepository deadLetterEventRepository = mock(DeadLetterEventRepository.class);
        PayCoreConsumer payCoreConsumer = mock(PayCoreConsumer.class);

        UUID id = UUID.randomUUID();
        DeadLetterEvent event = new DeadLetterEvent(
                id,
                "payment.captured",
                null,
                "{\"eventId\":\"evt_capture_001\"}",
                "PayCore event id is required",
                DeadLetterEventStatus.PENDING,
                0,
                OffsetDateTime.now(),
                null
        );

        when(deadLetterEventRepository.findPending(10)).thenReturn(List.of(event));

        DeadLetterReplayService service = new DeadLetterReplayService(
                deadLetterEventRepository,
                payCoreConsumer
        );

        int replayed = service.replayPending(10);

        assertThat(replayed).isEqualTo(1);
        verify(payCoreConsumer).consumePaymentCaptured(event.payload());
        verify(deadLetterEventRepository).markReplayed(id);
    }

    @Test
    void replayPendingReplaysPayCoreSettledEventAndMarksReplayed() {
        DeadLetterEventRepository deadLetterEventRepository = mock(DeadLetterEventRepository.class);
        PayCoreConsumer payCoreConsumer = mock(PayCoreConsumer.class);

        UUID id = UUID.randomUUID();
        DeadLetterEvent event = new DeadLetterEvent(
                id,
                "payment.settled",
                null,
                "{\"eventId\":\"evt_settle_001\"}",
                "PayCore currency must be a 3-letter uppercase code",
                DeadLetterEventStatus.PENDING,
                0,
                OffsetDateTime.now(),
                null
        );

        when(deadLetterEventRepository.findPending(10)).thenReturn(List.of(event));

        DeadLetterReplayService service = new DeadLetterReplayService(
                deadLetterEventRepository,
                payCoreConsumer
        );

        int replayed = service.replayPending(10);

        assertThat(replayed).isEqualTo(1);
        verify(payCoreConsumer).consumePaymentSettled(event.payload());
        verify(deadLetterEventRepository).markReplayed(id);
    }

    @Test
    void replayPendingRejectsUnsupportedTopic() {
        DeadLetterEventRepository deadLetterEventRepository = mock(DeadLetterEventRepository.class);
        PayCoreConsumer payCoreConsumer = mock(PayCoreConsumer.class);

        UUID id = UUID.randomUUID();
        DeadLetterEvent event = new DeadLetterEvent(
                id,
                "unknown.topic",
                null,
                "{\"eventId\":\"evt_unknown_001\"}",
                "Unsupported test event",
                DeadLetterEventStatus.PENDING,
                0,
                OffsetDateTime.now(),
                null
        );

        when(deadLetterEventRepository.findPending(10)).thenReturn(List.of(event));

        DeadLetterReplayService service = new DeadLetterReplayService(
                deadLetterEventRepository,
                payCoreConsumer
        );

        assertThatThrownBy(() -> service.replayPending(10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported dead letter source topic: unknown.topic");

        verify(payCoreConsumer, never()).consumePaymentCaptured(event.payload());
        verify(payCoreConsumer, never()).consumePaymentSettled(event.payload());
        verify(deadLetterEventRepository, never()).markReplayed(id);
    }
}
