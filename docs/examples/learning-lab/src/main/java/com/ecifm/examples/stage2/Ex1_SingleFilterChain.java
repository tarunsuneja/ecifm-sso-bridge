package com.ecifm.examples.stage2;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/*
 * ─────────────────────────────────────────────────────────────────
 * STAGE 2: Spring Security
 * Exercise 1: Single SecurityFilterChain (public vs protected)
 * ─────────────────────────────────────────────────────────────────
 *
 * Goal: See how one filter chain protects different URL patterns.
 *
 * Key points:
 *  - SecurityFilterChain is a list of filters + rules
 *  - requestMatchers("/public/**").permitAll() = no auth needed
 *  - requestMatchers("/admin/**").authenticated() = auth required
 *  - .formLogin() = redirect to login page (or return 401 for APIs)
 *  - csrf().disable() = needed for REST APIs using Bearer/session
 *
 * Try it:
 *  curl http://localhost:8080/public/ping       → OK (no auth)
 *  curl http://localhost:8080/admin/secret       → 302 redirect to /login
 *  curl http://localhost:8080/private/data       → 302 redirect to /login
 */
@RestController
class SingleChainController {

    @GetMapping("/public/ping")
    public String ping() {
        return "pong (public)";
    }

    @GetMapping("/admin/secret")
    public String secret() {
        return "you found the secret (authenticated)";
    }

    @GetMapping("/private/data")
    public String data() {
        return "sensitive data (authenticated)";
    }
}

/*
 * Security configuration: ONE chain with THREE URL matchers.
 * Note: @Order is NOT set, so this is the only/default chain.
 */
@Configuration
@EnableWebSecurity
class SingleChainConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/public/**").permitAll()         // open to all
                .requestMatchers("/admin/**").authenticated()      // needs login
                .anyRequest().authenticated()                      // everything else needs login
            )
            .formLogin(c -> {})                                    // enables login page
            .csrf(c -> c.disable());                               // needed for REST calls
        return http.build();
    }
}
