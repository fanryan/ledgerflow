package com.fanryan.ledgerflow.deadletter;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DeadLetterController {

    private final DeadLetterReplayService deadLetterReplayService;

    public DeadLetterController(DeadLetterReplayService deadLetterReplayService) {
        this.deadLetterReplayService = deadLetterReplayService;
    }

    @PostMapping("/admin/dead-letter/replay")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DeadLetterReplayResponse replayPending(
            @RequestParam(defaultValue = "10") int limit
    ) {
        int replayed = deadLetterReplayService.replayPending(limit);

        return new DeadLetterReplayResponse(replayed);
    }
}