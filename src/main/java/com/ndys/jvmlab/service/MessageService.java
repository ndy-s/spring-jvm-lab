package com.ndys.jvmlab.service;

import org.springframework.stereotype.Service;

@Service
public class MessageService {

    public String message() {
        return "Hello JVM Lab";
    }
}
