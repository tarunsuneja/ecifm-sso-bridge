package com.ecifm.examples.stage2;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/*
 * ─────────────────────────────────────────────────────────────────
 * STAGE 2: Spring Security
 * Exercise 2: Multiple Filter Chains with @Order
 * ─────────────────────────────────────────────────────────────────
 *
 * Goal: Understand how multiple chains match different URLs.
 *
 * Key points:
 *  - Lower @Order = higher priority (evaluated first)
 *  - First matching chain handles the request; others are skipped
 *  - Each chain has its own requestMatchers, auth rules, CSRF, etc.
 *  - Chains are completely independent — like isolated apps
 *
 * Flow:
 *  Request URL          → Matches Chain  → Handled by
 *  /api/public/ping     → Chain 1 (@Order 1) → public
 *  /api/admin/secret    → Chain 1         → authenticated
 *  /actuator/health     → Chain 2 (@Order 2) → public
 *  /anything/else       → Chain 3 (last)  → authenticated
 *
 * Try it:
 *  curl http://localhost:8080/api/public/ping       → "pong"
 *  curl http://localhost:8080/api/admin/secret       → 302 (needs auth, but no login page in Chain 1)
 *  curl http://localhost:8080/actuator/health        → "UP"
 *  curl http://localhost:8080/some/page              → 302 (Chain 3 has formLogin)
 */

/*
 * Chain 1 (@Order 1): Matches /api/**
 *   - /api/public/**  → permitAll
 *   - /api/admin/**   → authenticated
 *   - STATELESS (no session, no redirect — just 401)
 *   - No CSRF (REST API)
 */
@Configuration
@EnableWebSecurity
class MultiChainConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain apiChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/**")
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/public/**").permitAll()
                .requestMatchers("/api/admin/**").authenticated()
                .anyRequest().authenticated()
            )
            .sessionManagement(s -> s.sessionCreationPolicy(
                org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
            .csrf(c -> c.disable());
        return http.build();
    }

    /*
     * Chain 2 (@Order 2): Matches /actuator/**
     *   - All actuator paths → permitAll (health check only)
     *   - STATELESS
     */
    @Bean
    @Order(2)
    public SecurityFilterChain actuatorChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/actuator/**")
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .sessionManagement(s -> s.sessionCreationPolicy(
                org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
            .csrf(c -> c.disable());
        return http.build();
    }

    /*
     * Chain 3 (no @Order, default): Matches everything else
     *   - All remaining URLs need auth
     *   - IF_REQUIRED (supports login page + sessions)
     *   - formLogin enabled (redirects to /login)
     */
    @Bean
    public SecurityFilterChain defaultChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
            .formLogin(c -> {})
            .csrf(c -> c.disable());
        return http.build();
    }
}

// Simulate actuator endpoint
@RestController
class HealthController {
    @GetMapping("/actuator/health")
    public String health() { return "UP"; }
}
