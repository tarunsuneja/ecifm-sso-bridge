# TRIRIGA Authentication Implementation

## Problem

All TRIRIGA endpoints sit behind Liberty's OIDC social login filter. Requests with any `Authorization` header (Basic, Bearer) or requests without an existing session are intercepted and redirected to the Liberty OIDC login flow, preventing machine-to-machine API access.

## Solution: SOAP Auth → JSESSIONID → REST

The bridge authenticates once via TRIRIGA's SOAP (Business Connect) endpoint using HTTP Basic auth, extracts the authenticated `JSESSIONID` from the response, and reuses it for all subsequent REST/OSLC calls.

```
Bridge ──SOAP(HTTP Basic)──▶ TRIRIGA AuthenticationFilter
                                    │
                                    ▼
                            MASSignonService.signOn()
                                    │
                                    ▼
                            Liberty mTLS /v3/users/checkauthentication
                                    │
                                    ▼
                            HTTP 200 + Set-Cookie: JSESSIONID=...
                                    │
Bridge ◀──extract JSESSIONID───────┘
        │
        │  ──OSLC(Cookie: JSESSIONID=...)──▶ TRIRIGA OslcLoginHandler
        │                                    │
        │                                    ▼
        │                              Validate session → HTTP 200
        │
        │  ──SSOConnect REST(Cookie: JSESSIONID=...)──▶ TRIRIGA
        │                                    │
        │                                    ▼
        │                              Sync user groups
```

## Key Files

| File | Role |
|------|------|
| `service/TririgaWsClient.java` | SOAP client with HTTP Basic auth + JSESSIONID extraction |
| `service/MasApiClient.java` | REST client using JSESSIONID for SSOConnect calls |
| `service/MasSyncService.java` | Orchestrates group sync from Entra ID → TRIRIGA |
| `controller/LocalMockController.java` | Diagnostic endpoint (`/local/test-oslc`) |

## `TririgaWsClient`

Core SOAP client using Apache CXF.

### `createPort(String jsessionId)`
- Sends `Authorization: Basic <base64(user:pass)>` as HTTP header (not SOAP `BasicChallenge`)
- Optionally sends pre-existing `JSESSIONID` as `Cookie` header
- Sets `SESSION_MAINTAIN_PROPERTY = true` so CXF tracks cookies
- Lenient TLS (trust-all certs, CN check disabled)

### `getAuthenticatedSessionId()`
1. Creates a port with HTTP Basic auth (no pre-existing session)
2. Calls `getApplicationInfo()` — this authenticates and creates an HTTP session on TRIRIGA
3. Reads `Set-Cookie` from CXF response context (`Message.PROTOCOL_HEADERS`)
4. Extracts `JSESSIONID` via regex and returns it

Returns `null` if authentication fails or no cookie is found.

### Why HTTP Basic, not SOAP BasicChallenge

TRIRIGA's `AuthenticationFilter` (decompiled from `ibm-tririga.jar`) reads credentials from:

```java
String auth = req.getHeader("Authorization");  // HTTP header
```

It does **not** process the SOAP `<h:BasicChallenge>` header. The SOAP `BasicChallenge` element is what TRIRIGA **returns** in SOAP fault responses, not what it reads for authentication.

The filter delegates to `MASSignonService.signOn()`, which calls `MASInternalAPI.isUserAuthenticated()` — an mTLS call to Liberty's internal API at `/v3/users/checkauthentication` using the `tririga-tls.p12` client certificate.

## `MasApiClient`

REST client for TRIRIGA SSOConnect calls. Uses Spring `RestTemplate`.

### `syncUserGroups(userName, groupName)`
1. Calls `tririgaWsClient.getAuthenticatedSessionId()` to authenticate and get a session
2. Builds the SSOConnect URL from `mas.base-url` + `mas.context` + `mas.rest-api`
3. Sends `Cookie: JSESSIONID=<session>` header (no Bearer token)
4. URL-encodes userName and groupName parameters

Returns `true` on HTTP 2xx, `false` otherwise.

### SSL
Disabled globally via `HttpsURLConnection.setDefaultSSLSocketFactory()` with trust-all certs, matching the CXF client configuration.

## `MasSyncService`

Orchestrates the full sync:
1. Resolves Entra ID groups (via Microsoft Graph API using bearer token)
2. Calls `MasApiClient.syncUserGroups(email, groupName)` with the resolved groups

## Why Other Auth Methods Failed

| Method | Result | Reason |
|--------|--------|--------|
| SOAP `BasicChallenge` header | Auth ignored | TRIRIGA reads HTTP `Authorization`, not SOAP header |
| `Authorization: Bearer <token>` | OIDC redirect | Liberty intercepts Bearer tokens |
| `Authorization: Basic <base64>` alone (REST) | OIDC redirect | Liberty intercepts requests to REST endpoints |
| `Authorization: Basic <base64>` alone (SOAP) | Works! | SOAP endpoint + Basic bypasses Liberty filter |
| Unauthenticated JSESSIONID pre-fetch | null | OSLC endpoint requires auth to set a cookie |
| JSESSIONID from SOAP auth → REST | HTTP 200 | Authenticated session is valid for all TRIRIGA endpoints |

## Test Endpoint

`GET /local/test-oslc` on the bridge runs the full flow:
1. SOAP auth → verify `getApplicationInfo` succeeds
2. Extract `JSESSIONID` from CXF response
3. OSLC call with session cookie → verify HTTP 200 + user profile
