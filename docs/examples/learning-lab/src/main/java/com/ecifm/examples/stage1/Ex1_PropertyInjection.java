package com.ecifm.examples.stage1;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/*
 * ─────────────────────────────────────────────────────────────────
 * STAGE 1: Spring Boot Foundation
 * Exercise 1: @Value Property Injection
 * ─────────────────────────────────────────────────────────────────
 *
 * Goal: Understand how to inject values from application.yml
 *       into Java fields using @Value("${property.path}").
 *
 * Key points:
 *  - ${app.name} reads app.name from application.yml
 *  - ${FEATURES_DEMO_MODE:false} reads env var, defaults to false
 *  - @Value is evaluated at bean creation time
 *  - Works with env vars, system properties, and yml properties
 *
 * Try it:
 *  curl http://localhost:8080/stage1/info
 *  SPRING_PROFILES_ACTIVE=dev mvn spring-boot:run
 *  curl http://localhost:8080/stage1/info  (name changes to DEV)
 */
@RestController
public class Ex1_PropertyInjection {

    @Value("${app.name}")
    private String appName;

    @Value("${app.version}")
    private String version;

    @Value("${app.admin-email}")
    private String adminEmail;

    @Value("${app.features.demo-mode}")
    private boolean demoMode;

    // env var with default
    @Value("${FEATURES_DEMO_MODE:false}")
    private boolean envDemoMode;

    // system property with default
    @Value("${java.version}")
    private String javaVersion;

    @GetMapping("/stage1/info")
    public String info() {
        return String.format("""
            App:        %s v%s
            Admin:      %s
            Demo mode:  %s (yml) / %s (env)
            Java:       %s
            """,
            appName, version, adminEmail,
            demoMode, envDemoMode, javaVersion);
    }
}
