package com.fanryan.ledgerflow.deadletter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fanryan.ledgerflow.auth.AuthService;
import com.fanryan.ledgerflow.auth.LoginRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import com.fanryan.ledgerflow.support.IntegrationTestSupport;

@AutoConfigureMockMvc
class DeadLetterReplayFlowTest extends IntegrationTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthService authService;

    @Test
    void replayRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/admin/dead-letter/replay"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void replayReturnsAcceptedForAuthenticatedUser() throws Exception {
        String token = authService.login(
                new LoginRequest("admin@ledgerflow.local", "password")
        ).accessToken();

        mockMvc.perform(post("/admin/dead-letter/replay")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.replayed").exists());
    }
}
