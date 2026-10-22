package com.ndys.jvmlab.controller;

import com.ndys.jvmlab.service.LeakService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/lab/leak")
public class LeakController {

    private final LeakService service;

    public LeakController(LeakService service) {
        this.service = service;
    }

    @PostMapping("/start")
    public String start() {
        return service.start() ? "started" : "already running";
    }

    @PostMapping("/stop")
    public String stop() {
        return service.stop() ? "stopped" : "not running";
    }

    @PostMapping("/clear")
    public String clear() {
        service.clear();
        return "cleared";
    }

    @GetMapping("/status")
    public LeakService.LeakStatus status() {
        return service.status();
    }
}
