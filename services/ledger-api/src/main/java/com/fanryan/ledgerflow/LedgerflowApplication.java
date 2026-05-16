package com.fanryan.ledgerflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import com.fanryan.ledgerflow.auth.JwtProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(JwtProperties.class)
public class LedgerflowApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerflowApplication.class, args);
    }
}