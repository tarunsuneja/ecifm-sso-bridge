package com.ecifm.examples.stage6;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;

/*
 * ─────────────────────────────────────────────────────────────────
 * STAGE 6: CXF SOAP & TRIRIGA Auth Internals
 * Exercise 2: Auth Method Comparison (TRIRIGA)
 * ─────────────────────────────────────────────────────────────────
 *
 * Goal: See which auth methods work on TRIRIGA Liberty
 *       and which fail — and understand WHY.
 *
 * This demonstrates the 5 methods we tested during
 * the real investigation, using raw HTTP clients.
 *
 * Liberty's OIDC filter intercepts these paths:
 *   /app/**       → React SPA (no direct auth)
 *   /login        → OIDC redirect
 *   /oidcclient/** → Liberty OIDC client
 *   /*             → Most endpoints redirect to /login
 *
 * The SOAP endpoint /ws/TririgaWS has its OWN auth filter
 * that reads Authorization: Basic BEFORE the OIDC filter.
 *
 * ─────────────────────────────────────────────────────────────────
 * Results (from real investigation)
 * ─────────────────────────────────────────────────────────────────
 *
 * Method                          → Result
 * ──────────────────────────────────────────────────────────────
 * SOAP BasicChallenge (body)      → FAIL (TRIRIGA ignores body auth)
 * Authorization: Bearer (REST)    → FAIL (OIDC filter redirects)
 * Authorization: Basic (REST)     → FAIL (OIDC filter redirects)
 * Authorization: Basic (SOAP)     → ✅ WORKS (separate filter)
 * Cookie: JSESSIONID=...          → ✅ WORKS (if session exists)
 */
public class Ex2_AuthMethodComparison {

    static final String TRIRIGA_BASE = "https://main.facilities.inst1.apps.npos2.ecifmdev.net";
    static final String SOAP_URL = TRIRIGA_BASE + "/ws/TririgaWS";
    static final String USER = "tririga_user";
    static final String PASS = "tririga_pass";

    static final HttpClient client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    public static void main(String[] args) throws Exception {
        System.out.println("=== TRIRIGA Auth Method Comparison ===\n");

        // Method 1: Basic auth on REST endpoint (will fail)
        testMethod("1. Authorization: Basic on REST /login",
            HttpRequest.newBuilder()
                .uri(URI.create(TRIRIGA_BASE + "/login"))
                .header("Authorization", basicAuth(USER, PASS))
                .build(),
            "Expected: 302 redirect to Core IDP (OIDC filter intercepts)");

        // Method 2: Bearer token on REST endpoint (will fail)
        testMethod("2. Authorization: Bearer on REST /login",
            HttpRequest.newBuilder()
                .uri(URI.create(TRIRIGA_BASE + "/login"))
                .header("Authorization", "Bearer fake.jwt.token")
                .build(),
            "Expected: 302 redirect to Core IDP (OIDC filter intercepts)");

        // Method 3: No auth on SOAP endpoint (will fail)
        testMethod("3. No auth on SOAP endpoint",
            HttpRequest.newBuilder()
                .uri(URI.create(SOAP_URL))
                .header("Content-Type", "text/xml")
                .POST(HttpRequest.BodyPublishers.ofString(
                    "<soap:Envelope xmlns:soap='http://schemas.xmlsoap.org/soap/envelope/'><soap:Body/></soap:Envelope>"
                ))
                .build(),
            "Expected: SOAP fault (auth required) or 302 redirect");

        // Method 4: Basic auth on SOAP endpoint (WORKS)
        testMethod("4. Authorization: Basic on SOAP endpoint ✓",
            HttpRequest.newBuilder()
                .uri(URI.create(SOAP_URL))
                .header("Authorization", basicAuth(USER, PASS))
                .header("Content-Type", "text/xml")
                .POST(HttpRequest.BodyPublishers.ofString(
                    "<soap:Envelope xmlns:soap='http://schemas.xmlsoap.org/soap/envelope/'><soap:Body/></soap:Envelope>"
                ))
                .build(),
            "Expected: SOAP response (TRIRIGA's auth filter accepts Basic on /ws/TririgaWS)");

        System.out.println("\n=== Why does Method 4 work? ===");
        System.out.println("""
            TRIRIGA Liberty has TWO auth mechanisms:
            
            1. Liberty OIDC filter (all URLs)
               - Intercepts Authorization: Bearer
               - Intercepts Authorization: Basic (on non-SOAP URLs)
               - Redirects to Core IDP for OIDC auth
               - URL patterns: /* (default)
            
            2. TRIRIGA AuthenticationFilter (only /ws/TririgaWS)
               - Reads Authorization: Basic header directly
               - Calls MASSignonService → Core IDP internal API
               - Returns JSESSIONID in Set-Cookie
               - Runs BEFORE the OIDC filter on this specific path
            
            The SOAP endpoint bypasses Liberty's OIDC filter
            because TRIRIGA's own filter processes the request
            first and handles auth natively.
            """);

        System.out.println("=== Auth chain (from decompiled code) ===");
        System.out.println("""
            1. HTTP Request → /ws/TririgaWS
            2. AuthenticationFilter reads: req.getHeader("Authorization")
               → "Basic <base64>"
            3. Decodes base64 → username:password
            4. Calls MASSignonService.signOn(username, password)
            5. MASSignonService calls MASInternalAPI.isUserAuthenticated()
               → mTLS to: https://internalapi.mas-inst1-core.svc:443/v3/users/checkauthentication
            6. Core IDP validates → returns user info
            7. TRIRIGA creates HTTP session
            8. Response: Set-Cookie: JSESSIONID=<session-id>
            """);
    }

    static void testMethod(String label, HttpRequest request, String expectation) throws Exception {
        System.out.println(label);
        System.out.println("  " + expectation);

        try {
            HttpResponse<String> resp = client.send(request,
                HttpResponse.BodyHandlers.ofString());

            int status = resp.statusCode();
            String location = resp.headers().firstValue("location").orElse("-");
            String setCookie = resp.headers().firstValue("set-cookie").orElse("-");

            System.out.printf("  Status: %d%n", status);
            if (status == 302) System.out.printf("  Location: %s%n",
                location.length() > 100 ? location.substring(0, 100) + "..." : location);
            if (resp.headers().allValues("set-cookie").size() > 0)
                System.out.printf("  Set-Cookie: %s%n",
                    setCookie.length() > 80 ? setCookie.substring(0, 80) + "..." : setCookie);
            System.out.println();
        } catch (Exception e) {
            System.out.println("  ERROR: " + e.getMessage() + "\n");
        }
    }

    static String basicAuth(String user, String pass) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + pass).getBytes());
    }
}
