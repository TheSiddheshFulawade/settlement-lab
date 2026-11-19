package com.siddheshfulawade.settlementlab.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Starts the HTTP API around the settlement engine. */
@SpringBootApplication
public class SettlementApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(SettlementApiApplication.class, args);
    }
}
