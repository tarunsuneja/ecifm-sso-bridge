package com.ecifm.examples.stage1;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/*
 * ─────────────────────────────────────────────────────────────────
 * STAGE 1: Spring Boot Foundation
 * Exercise 3: @Configuration + @Bean
 * ─────────────────────────────────────────────────────────────────
 *
 * Goal: Understand programmatic bean definitions with @Bean.
 *
 * Key points:
 *  - @Configuration tells Spring the class has @Bean methods
 *  - @Bean methods return objects that Spring manages as beans
 *  - The method name becomes the bean name (e.g., "appMetadata")
 *  - Beans can be injected into other beans via constructor
 *  - Use @Bean when you need custom init logic (not just new)
 *
 * Try it:
 *  curl http://localhost:8080/stage1/metadata
 *  → JSON: {"name":"learning-lab","version":"1.0.0","adminEmail":"admin@example.com"}
 */

/*
 * A POJO (Plain Old Java Object) — no Spring annotations needed.
 * Spring creates and manages it via the @Bean method below.
 */
class AppMetadata {
    private final String name;
    private final String version;
    private final String adminEmail;

    AppMetadata(String name, String version, String adminEmail) {
        this.name = name;
        this.version = version;
        this.adminEmail = adminEmail;
    }

    public String getName() { return name; }
    public String getVersion() { return version; }
    public String getAdminEmail() { return adminEmail; }
}

/*
 * The @Configuration class — like a recipe for creating beans.
 * Spring calls these methods during startup and caches the results.
 */
@Configuration
class AppConfig {

    @Value("${app.name}")
    private String appName;

    @Value("${app.version}")
    private String version;

    @Value("${app.admin-email}")
    private String adminEmail;

    @Bean
    public AppMetadata appMetadata() {
        // You could add logging, validation, or DB lookup here
        System.out.println("Creating AppMetadata bean: " + appName);
        return new AppMetadata(appName, version, adminEmail);
    }
}

@RestController
class MetadataController {

    private final AppMetadata appMetadata;

    public MetadataController(AppMetadata appMetadata) {
        this.appMetadata = appMetadata;
    }

    @GetMapping("/stage1/metadata")
    public AppMetadata metadata() {
        return appMetadata;
    }
}
