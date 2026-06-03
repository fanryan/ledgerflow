package com.fanryan.ledgerflow;

import com.fanryan.ledgerflow.auth.JwtProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(JwtProperties.class)
public class LedgerflowApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerflowApplication.class, args);
    }
}