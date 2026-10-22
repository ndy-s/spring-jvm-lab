package com.ndys.jvmlab.controller;

import com.ndys.jvmlab.service.MessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/hello")
public class HelloController {

    private static final Logger log = LoggerFactory.getLogger(HelloController.class);

    private final MessageService service;

    public HelloController(MessageService service) {
        this.service = service;
    }

    @GetMapping
    public String hello() {
        log.info("hello called");
        return service.message();
    }

}
