package com.ecifm.examples.stage1;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/*
 * ─────────────────────────────────────────────────────────────────
 * STAGE 1: Spring Boot Foundation
 * Exercise 4: Profile-based Configuration
 * ─────────────────────────────────────────────────────────────────
 *
 * Goal: See how different profiles load different properties.
 *
 * Key points:
 *  - application.yml has default properties
 *  - application-dev.yml overrides for development
 *  - application-prod.yml overrides for production
 *  - Activate with: SPRING_PROFILES_ACTIVE=dev
 *  - Default profile (no active profile) = application.yml only
 *
 * Try it:
 *  mvn spring-boot:run
 *  curl http://localhost:8080/stage1/profile
 *  → "App: learning-lab | Demo: true | Rate limit: 100"
 *
 *  SPRING_PROFILES_ACTIVE=prod mvn spring-boot:run
 *  curl http://localhost:8080/stage1/profile
 *  → "App: learning-lab (PROD) | Demo: false | Rate limit: 1000"
 */
@RestController
public class Ex4_ProfileDemo {

    @Value("${app.name}")
    private String appName;

    @Value("${app.features.demo-mode}")
    private boolean demoMode;

    @Value("${app.features.rate-limit}")
    private int rateLimit;

    @GetMapping("/stage1/profile")
    public String profile() {
        return String.format("""
            App:        %s
            Demo mode:  %s
            Rate limit: %d
            """, appName, demoMode, rateLimit);
    }
}
