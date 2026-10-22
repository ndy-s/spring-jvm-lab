package com.ndys.jvmlab.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.ThreadLocalRandom;

@RestController
@RequestMapping("/lab/fail")
public class FailureController {

    @GetMapping
    public ResponseEntity<String> fail(@RequestParam(defaultValue = "1.0") double rate) {
        if (rate < 0 || rate > 1) {
            return ResponseEntity.badRequest().body("rate must be between 0 and 1");
        }
        if (ThreadLocalRandom.current().nextDouble() < rate) {
            throw new IllegalStateException("simulated failure (rate=" + rate + ")");
        }
        return ResponseEntity.ok("ok");
    }
}
