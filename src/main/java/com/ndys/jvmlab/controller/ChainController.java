package com.ndys.jvmlab.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.concurrent.ThreadLocalRandom;

@RestController
@RequestMapping("/lab/chain")
public class ChainController {

    private static final Logger log = LoggerFactory.getLogger(ChainController.class);

    private final RestClient client;

    // The builder must come from Spring: only the auto-configured builder is instrumented for tracing.
    public ChainController(RestClient.Builder builder, @Value("${server.port:8080}") int port) {
        this.client = builder.baseUrl("http://localhost:" + port).build();
    }

    @GetMapping
    public String chain() throws InterruptedException {
        log.info("chain started");

        String first = client.get().uri("/hello").retrieve().body(String.class);

        long workMillis = ThreadLocalRandom.current().nextLong(20, 120);
        Thread.sleep(workMillis);

        String second = client.get().uri("/hello").retrieve().body(String.class);

        log.info("chain finished after {} ms of simulated work", workMillis);
        return first + " -> " + second;
    }
}
