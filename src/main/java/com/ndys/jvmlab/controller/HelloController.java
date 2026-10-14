package com.ndys.jvmlab.controller;

import com.ndys.jvmlab.service.MessageService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/hello")
public class HelloController {

    private final MessageService service;

    public HelloController(MessageService service) {
        this.service = service;
    }

    @GetMapping
    public String hello() {
        return service.message();
    }

}
