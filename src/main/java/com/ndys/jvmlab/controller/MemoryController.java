package com.ndys.jvmlab.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/lab")
public class MemoryController {

    @GetMapping("/allocate")
    public String allocate() {
        List<byte[]> data = new ArrayList<>();

        for (int i = 0; i < 200; i++) {
            data.add(new byte[1024 * 1024]);
        }

        return "Done";
    }
}
