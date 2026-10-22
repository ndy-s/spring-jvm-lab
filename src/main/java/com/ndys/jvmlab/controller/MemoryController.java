package com.ndys.jvmlab.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/lab")
public class MemoryController {

    // Defaults (200 x 1 MB) make every array a G1 humongous object with a 256 MB heap.
    // Use chunkKb below half the G1 region size (e.g. 64) for ordinary young-gen churn.
    @GetMapping("/allocate")
    public ResponseEntity<String> allocate(@RequestParam(defaultValue = "200") int mb,
                                           @RequestParam(defaultValue = "1024") int chunkKb) {
        if (mb < 1 || mb > 1024 || chunkKb < 1 || chunkKb > 4096) {
            return ResponseEntity.badRequest().body("mb must be 1..1024, chunkKb must be 1..4096");
        }

        int chunkBytes = chunkKb * 1024;
        long chunks = (long) mb * 1024 / chunkKb;
        List<byte[]> data = new ArrayList<>();

        for (long i = 0; i < chunks; i++) {
            data.add(new byte[chunkBytes]);
        }

        return ResponseEntity.ok("Done: allocated " + data.size() + " x " + chunkKb + " KB");
    }
}
