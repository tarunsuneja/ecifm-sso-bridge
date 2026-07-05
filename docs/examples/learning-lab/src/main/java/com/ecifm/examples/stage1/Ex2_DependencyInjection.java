package com.ecifm.examples.stage1;

import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/*
 * ─────────────────────────────────────────────────────────────────
 * STAGE 1: Spring Boot Foundation
 * Exercise 2: Dependency Injection (@Service + @RestController)
 * ─────────────────────────────────────────────────────────────────
 *
 * Goal: See how @Service and @RestController wire together
 *       via constructor injection (the recommended approach).
 *
 * Key points:
 *  - @Service marks a class as a business logic component
 *  - @RestController marks it as a REST endpoint
 *  - Constructor injection: Spring finds the only constructor
 *    and auto-wires the GreetingService into the controller
 *  - No @Autowired needed when there's a single constructor
 *
 * Try it:
 *  curl "http://localhost:8080/stage1/greet?name=Tarun"
 *  → "Hello, Tarun! Welcome to learning-lab v1.0.0"
 */
@RestController
public class Ex2_DependencyInjection {

    private final GreetingService greetingService;

    // Constructor injection: Spring auto-wires GreetingService here
    public Ex2_DependencyInjection(GreetingService greetingService) {
        this.greetingService = greetingService;
    }

    @GetMapping("/stage1/greet")
    public String greet(@RequestParam(defaultValue = "World") String name) {
        return greetingService.greet(name);
    }
}

/*
 * The service layer — contains business logic.
 * Spring manages its lifecycle (creates once, reuses everywhere).
 */
@Service
class GreetingService {

    private final AppMetadata appMetadata;

    public GreetingService(AppMetadata appMetadata) {
        this.appMetadata = appMetadata;
    }

    public String greet(String name) {
        return "Hello, %s! Welcome to %s v%s".formatted(
            name, appMetadata.getName(), appMetadata.getVersion());
    }
}
