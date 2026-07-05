package com.ecifm.examples.stage8;

/*
 * ─────────────────────────────────────────────────────────────────
 * STAGE 8: Debugging Methodology
 * ─────────────────────────────────────────────────────────────────
 *
 * This is NOT runnable code — it's a reference of the exact
 * commands and techniques used during the real investigation.
 *
 * The most important skill: READ THE ERROR MESSAGE.
 *
 * ─────────────────────────────────────────────────────────────────
 * The Four-Step Investigation Loop
 * ─────────────────────────────────────────────────────────────────
 *
 *   Step 1: State a hypothesis
 *   Step 2: Test the hypothesis
 *   Step 3: Compare result with expectation
 *   Step 4: Investigate WHY it differed
 *   → Back to Step 1 with new understanding
 *
 * ─────────────────────────────────────────────────────────────────
 * Exercise 1: Essential curl commands
 * ─────────────────────────────────────────────────────────────────
 *
 * # 1a. See EVERYTHING (headers, TLS handshake, redirects)
 * curl -skv "https://main.facilities.inst1.apps.npos2.ecifmdev.net/login"
 *
 * # 1b. Show only critical response headers
 * curl -sk -D - "https://host/path" 2>&1 |
 *   Select-String "HTTP/|location:|Set-Cookie:|< HTTP"
 *
 * # 1c. Follow redirects AND show all headers
 * curl -skL -D - "https://host/path" 2>&1
 *
 * # 1d. Send a specific Cookie header
 * curl -sk -H "Cookie: JSESSIONID=abc123" "https://host/path"
 *
 * # 1e. Send a specific Authorization header
 * curl -sk -H "Authorization: Bearer <token>" "https://host/path"
 *
 * ─────────────────────────────────────────────────────────────────
 * Exercise 2: Decoding a JWT
 * ─────────────────────────────────────────────────────────────────
 *
 * # 2a. Split the JWT into its three parts
 * $jwt = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ0YXJ1biJ9.signature"
 * $parts = $jwt.Split('.')
 *
 * # 2b. Decode the header
 * $header = [System.Text.Encoding]::UTF8.GetString(
 *     [System.Convert]::FromBase64String($parts[0]))
 * Write-Output $header
 * # → {"alg":"RS256"}
 *
 * # 2c. Decode the payload
 * $payload = [System.Text.Encoding]::UTF8.GetString(
 *     [System.Convert]::FromBase64String($parts[1]))
 * Write-Output $payload
 * # → {"sub":"user@example.com","iss":"https://..."}
 *
 * # 2d. Or paste the full JWT at https://jwt.io
 *
 * ─────────────────────────────────────────────────────────────────
 * Exercise 3: Tracing the redirect chain
 * ─────────────────────────────────────────────────────────────────
 *
 * # This is EXACTLY what we did during the real investigation:
 *
 * # Step 1: Hit TRIRIGA
 * curl -skv "https://main.facilities.inst1.apps.npos2.ecifmdev.net/app/tririga"
 * # Expected: HTTP 200 (React SPA, not a redirect!)
 * # → SPA has JavaScript that redirects to /login
 * # → Lesson: Modern apps don't do server-side redirects anymore
 *
 * # Step 2: Hit the login URL
 * curl -skL -D - "https://main.facilities.inst1.apps.npos2.ecifmdev.net/login"
 * # Expected: 302 with location = Core IDP's authorize endpoint
 * # → Shows Liberty's OIDC client configuration in action
 *
 * # Step 3: Follow what the browser would do
 * # (The location from Step 2)
 * curl -skv "https://auth.inst1.apps.npos2.ecifmdev.net/oidc/endpoint/MaximoAppSuite/authorize?response_type=code&client_id=facilities&redirect_uri=https://main.facilities.inst1.apps.npos2.ecifmdev.net/oidcclient/redirect/facilities&state=abcdef"
 * # Expected: 302 to Core IDP's SPA (/login/) or bridge
 * # → Shows which upstream provider is configured
 *
 * # Step 4: Check if auth.inst1... is the bridge or a different server
 * curl -skv "https://auth.inst1.apps.npos2.ecifmdev.net/" | Select-String "x-masidp"
 * # If x-masidp: true → it's the MAS Core IDP, NOT the bridge
 *
 * ─────────────────────────────────────────────────────────────────
 * Exercise 4: OpenShift debugging commands
 * ─────────────────────────────────────────────────────────────────
 *
 * # 4a. View pod logs (last 50 lines)
 * oc logs deployment/ecifm-sso-bridge --tail=50
 *
 * # 4b. Follow logs in real time
 * oc logs deployment/ecifm-sso-bridge --tail=20 -f
 *
 * # 4c. Search logs for patterns
 * oc logs deployment/ecifm-sso-bridge --tail=100 |
 *   Select-String "Liberty|Step|Error|Exception"
 *
 * # 4d. Describe a deployment (env vars, probes, volumes)
 * oc describe deployment/ecifm-sso-bridge
 *
 * # 4e. Describe a StatefulSet (for MAS Liberty)
 * oc describe statefulset/inst1-main-appserver -n mas-inst1-facilities
 *
 * # 4f. Get YAML of a specific field
 * oc get secret inst1-main-credentials-oauth-facilities-liberty
 *     -n mas-inst1-facilities
 *     -o jsonpath="{.data['oidc\.xml']}"
 *
 * # 4g. Decode the base64 value
 * $b64 = "...";
 * [System.Text.Encoding]::UTF8.GetString(
 *     [System.Convert]::FromBase64String($b64))
 *
 * # 4h. Port-forward for local testing
 * oc port-forward deployment/ecifm-sso-bridge 8080:8080
 * # In another terminal:
 * curl http://localhost:8080/actuator/health
 *
 * # 4i. Check routes
 * oc get routes --all-namespaces | Select-String "inst1"
 *
 * ─────────────────────────────────────────────────────────────────
 * Exercise 5: Error Message Decoder
 * ─────────────────────────────────────────────────────────────────
 *
 * Error: CWOAU0073E
 *   "The uniqueSecurityName cannot be mapped to a TRIRIGA user entry"
 *
 *   Meaning: Liberty received a token where the 'sub' claim
 *   doesn't match any person record in TRIRIGA.
 *
 *   Root cause: The OIDC token's sub is "mas-facilities"
 *   (from client_credentials grant) instead of "user@example.com"
 *   (from authorization_code grant).
 *
 *   Fix: Use authorization_code grant so the sub is the real user,
 *   AND add a tokenCustomizer that sets uniqueSecurityName claim.
 *
 * ─────────────────────────────────────────────────────────────────
 * Error: HTTP 500 on /oidcclient/redirect
 *
 *   Meaning: Liberty's OIDC client failed during code exchange.
 *
 *   Possible causes:
 *   a) The authorization code was generated by a different issuer
 *      (bridge) than Liberty's configured issuer (Core IDP)
 *   b) The state parameter doesn't match Liberty's session
 *   c) The redirect URL doesn't match the registered client config
 *
 *   Investigation: Decode Liberty's oidc.xml to find issuerIdentifier
 *
 * ─────────────────────────────────────────────────────────────────
 * Error: No redirect from TRIRIGA (HTTP 200 instead of 302)
 *
 *   Meaning: TRIRIGA is not doing a server-side redirect.
 *
 *   Root cause: TRIRIGA's modern UI is a React SPA. The redirect
 *   happens in JavaScript after the page loads.
 *
 *   Fix: Don't curl /app/tririga. Use /login instead, which DOES
 *   do a server-side redirect (Liberty's OIDC filter).
 *
 * ─────────────────────────────────────────────────────────────────
 * The Golden Rule
 * ─────────────────────────────────────────────────────────────────
 *
 * "The error message tells you WHAT is wrong.
 *  The error message cannot tell you WHY it is wrong.
 *  You must investigate to find the root cause."
 *
 * Example:
 *   CWOAU0073E says: "can't map sub to user"
 *   It does NOT say: "because you used client_credentials"
 *   You discover the WHY by looking at the token's sub claim.
 */
public class DebuggingMethodology {
    // This class is intentionally empty — it's a documentation file
}
