package com.ecifm.examples.stage2;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/*
 * ─────────────────────────────────────────────────────────────────
 * STAGE 2: Spring Security
 * Exercise 3: In-Memory Users with Roles
 * ─────────────────────────────────────────────────────────────────
 *
 * Goal: Set up users and role-based access (RBAC).
 *
 * Key points:
 *  - InMemoryUserDetailsManager stores users in RAM (dev only)
 *  - Roles like "USER", "ADMIN" are mapped via hasRole()
 *  - .hasRole("ADMIN") checks if user has ROLE_ADMIN authority
 *  - .hasAnyRole("USER", "ADMIN") allows either role
 *  - In production, use LDAP, DB, or OAuth2 instead
 *
 * Users created:
 *  admin / adminpass → has ADMIN + USER roles
 *  user  / userpass  → has USER role only
 *
 * Try it:
 *  curl -u user:userpass http://localhost:8080/app/profile
 *  curl -u admin:adminpass http://localhost:8080/app/profile
 *  curl -u user:userpass http://localhost:8080/app/admin
 *  → 403 Forbidden
 *  curl -u admin:adminpass http://localhost:8080/app/admin
 *  → "Admin dashboard"
 */
@RestController
class AppController {

    @GetMapping("/app/profile")
    public String profile() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
            .getContext().getAuthentication();
        return "Logged in as: " + auth.getName()
            + " | Roles: " + auth.getAuthorities();
    }

    @GetMapping("/app/admin")
    public String admin() {
        return "Admin dashboard – only admins see this";
    }
}

@Configuration
@EnableWebSecurity
class UserConfig {

    @Bean
    public SecurityFilterChain chain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/app/profile").hasAnyRole("USER", "ADMIN")
                .requestMatchers("/app/admin").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .httpBasic(c -> {})    // Accept Authorization: Basic header
            .csrf(c -> c.disable());
        return http.build();
    }

    /*
     * Creates two users in memory.
     * {noop} means "no password encoding" — plain text.
     * In production, use bcrypt: {bcrypt}$2a$10$...
     */
    @Bean
    public UserDetailsService users() {
        var admin = User.withUsername("admin")
            .password("{noop}adminpass")
            .roles("USER", "ADMIN")
            .build();
        var user = User.withUsername("user")
            .password("{noop}userpass")
            .roles("USER")
            .build();
        return new InMemoryUserDetailsManager(admin, user);
    }
}
