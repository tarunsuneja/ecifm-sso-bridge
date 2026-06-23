# ecifm-saml-bridge

**Version:** 0.1.0  
**Type:** Spring Boot 3.3.5 / Java 17 / JAR  
**Purpose:** Containerized SAML SSO bridge for MAS (Maximo Application Suite) on OpenShift.  
**Replaces:** `ecifmSaml` (legacy WAR deployed on WebSphere)

---

## Architecture Overview

```
Browser ──► MAS SSO (Keycloak) ──► Entra ID (Azure AD) ──► JWT
                                                             │
                                                             ▼
                                              ┌─────────────────────────┐
                                              │  ecifm-saml-bridge      │
                                              │  (this project)         │
                                              │                         │
                                              │  1. Validate JWT        │
                                              │  2. Extract user/groups │
                                              │  3. Fallback: Graph API │
                                              │  4. Call MAS SSOConnect │
                                              │  5. Redirect to MAS     │
                                              └─────────────────────────┘
```

### Request flow (detailed)

1. User visits `https://<bridge-host>/`
2. Redirected to `/redirect`
3. Spring Security validates the `Authorization: Bearer <jwt>` header against MAS SSO
4. Controller extracts user email and AD groups from JWT claims
5. If JWT has no groups (or overage indicator), fallback to Microsoft Graph API
6. Bridge calls `MAS SSOConnect REST API` with Bearer token + user info
7. MAS updates `triPeople` record and triggers `cstValidateADGroup` workflow
8. User is redirected to MAS/TRIRIGA home

---

## Project Structure

```
ecifm-saml-bridge/
├── pom.xml                          # Maven build (Spring Boot 3.3.5, Java 17)
├── Dockerfile                       # Multi-stage container build
├── openshift/
│   ├── configmap.yaml               # Environment config (update these values)
│   ├── deployment.yaml              # OpenShift Deployment (2 replicas)
│   ├── service.yaml                 # Internal load balancer
│   └── route.yaml                   # Public HTTPS URL
├── src/main/java/com/ecifm/saml/bridge/
│   ├── EcifmSamlBridgeApplication.java   # Spring Boot entry point
│   ├── config/
│   │   └── SecurityConfig.java           # OAuth2 Resource Server + local test endpoints
│   ├── controller/
│   │   ├── AcsHandlerController.java     # Main SSO flow (/redirect)
│   │   └── LocalMockController.java      # Local test mocks (/local/*)
│   ├── service/
│   │   ├── MasSyncService.java           # Orchestrates group resolution + MAS sync
│   │   ├── MasApiClient.java             # REST client to call MAS SSOConnect
│   │   └── EntraIdGroupResolver.java     # Microsoft Graph API group fallback
│   └── model/
│       └── UserInfo.java                 # User email + groups model
└── src/main/resources/
    ├── application.yml                   # Default config (local dev)
    ├── application-openshift.yml         # OpenShift profile config
    └── logback-spring.xml                # Logging config
```

---

## Prerequisites

| Tool | Version | Purpose |
|------|---------|---------|
| JDK | 17+ | Compile and run |
| Maven | 3.9+ | Build the JAR |
| Docker | latest | Build container image |
| OpenShift CLI (`oc`) | latest | Deploy to OpenShift |
| OpenShift cluster | 4.x | Target platform |

---

## Step 1 — Configure Environment Variables

The app reads all configuration from environment variables. Defaults are in `application.yml`.

### ConfigMap to update (`openshift/configmap.yaml`)

```yaml
data:
  MAS_BASE_URL: "https://mas-tririga.apps.ocp.example.com"     # [REQUIRED] Your MAS/TRIRIGA route URL
  MAS_CONTEXT: "/tririga"                                       # Usually /tririga
  MAS_REST_API: "/html/en/default/rest/SSOConnect?userName={0}&adGroupName={1}"  # SSOConnect endpoint
  JWT_ISSUER_URI: "https://mas-sso.apps.ocp.example.com/auth/realms/mas"         # [REQUIRED] MAS SSO realm URL
  GRAPH_API_ENABLED: "true"                                      # Enable Graph API fallback
  SPRING_PROFILES_ACTIVE: "openshift"                            # Activates application-openshift.yml
```

### Where to get each value

| Variable | Where to find it |
|----------|-----------------|
| `MAS_BASE_URL` | `oc get routes -n <mas-namespace>` — the MAS/TRIRIGA route |
| `JWT_ISSUER_URI` | `oc get routes -n <mas-sso-namespace>` — the MAS SSO (Keycloak) route + `/auth/realms/<realm>` |
| `MAS_CONTEXT` | Usually `/tririga`. Confirm with MAS admin |
| `MAS_REST_API` | Default SSOConnect endpoint path. Change only if MAS has a different REST path |
| `GRAPH_API_ENABLED` | Set to `false` if you don't need Microsoft Graph API fallback |

### Microsoft Entra ID / Azure AD prerequisites

To use the Graph API fallback (for JWT with >200 groups), the Entra ID app registration must have:

1. **API Permissions** (delegated):
   - `Microsoft Graph / GroupMember.Read.All`
   - `Microsoft Graph / User.Read`

2. **Token configuration**:
   - `groupMembershipClaims: "SecurityGroup"`

3. **MAS SSO (Keycloak)** must be configured with Entra ID as an OIDC identity provider:

   | Field | Value |
   |-------|-------|
   | Issuer | `https://login.microsoftonline.com/{tenant-id}/v2.0` |
   | Authorization URL | `https://login.microsoftonline.com/{tenant-id}/oauth2/v2.0/authorize` |
   | Token URL | `https://login.microsoftonline.com/{tenant-id}/oauth2/v2.0/token` |
   | JWKS URL | `https://login.microsoftonline.com/{tenant-id}/discovery/v2.0/keys` |
   | Client ID | From Entra ID app registration |
   | Client Secret | From Entra ID app registration |

---

## Step 2 — Build the JAR

```powershell
# Set JAVA_HOME to your JDK 17 installation
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"

# Build
mvn clean package -DskipTests

# Output: target/ecifm-saml-bridge.jar
```

---

## Step 3 — Run Locally (for testing)

### Without MAS SSO (mock mode)

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"

# Start the app
java -jar target/ecifm-saml-bridge.jar
```

Then test:

```powershell
# Basic health check
curl http://localhost:8080/test

# Mock SSOConnect call
curl "http://localhost:8080/local/mock-sso?userName=test@ecifm.com&adGroupName=GROUP_A"

# Mock redirect flow
curl "http://localhost:8080/local/mock-redirect?userName=test@ecifm.com&groups=GROUP_A,GROUP_B"

# Actuator health (used by OpenShift probes)
curl http://localhost:8080/actuator/health
```

Expected responses:
- `/test` → `ecifm-saml-bridge is running`
- `/local/mock-sso` → `Local mock SSOConnect executed successfully`
- `/local/mock-redirect` → `Mock redirect to: http://localhost:8080/tririga`

### With actual MAS SSO (requires cluster)

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
$env:JWT_ISSUER_URI = "https://<your-mas-sso-host>/auth/realms/<realm>"
$env:MAS_BASE_URL = "https://<your-mas-host>"

java -jar target/ecifm-saml-bridge.jar
```

Then access `http://localhost:8080/` — it will redirect to MAS SSO login.

---

## Step 4 — Build Docker Image

```powershell
# Build
docker build -t ecifm-saml-bridge:0.1.0 .

# Verify
docker images | findstr ecifm-saml-bridge
```

---

## Step 5 — Deploy to OpenShift (via Web Console)

> **Before you start:** have your OpenShift cluster URL, project name, `MAS_BASE_URL`, and `JWT_ISSUER_URI` ready.

---

### 5A. Import from Git (Recommended)

This is the simplest path — OpenShift reads the repo, detects the `Dockerfile`, builds the image, and deploys.

| Step | Action |
|------|--------|
| 1 | OpenShift Console → select your project → click **+Add** → **Import from Git** |
| 2 | **Git Repo URL:** `https://github.com/ecifm-solutions/ecifm-saml-bridge.git` |
| 3 | **Builder:** Select **Dockerfile** (do not select "Java") |
| 4 | **Application Name:** `ecifm-saml-bridge` |
| 5 | **Name:** `ecifm-saml-bridge` |
| 6 | **Container Port:** `8080` |
| 7 | Click **Advanced Options**, go to **Environment** tab |

Add these environment variables:

| Name | Value |
|------|-------|
| `MAS_BASE_URL` | `https://<your-mas-host>` |
| `MAS_CONTEXT` | `/tririga` |
| `JWT_ISSUER_URI` | `https://<your-mas-sso-host>/auth/realms/<realm>` |
| `GRAPH_API_ENABLED` | `true` |
| `SPRING_PROFILES_ACTIVE` | `openshift` |

| 8 | Click **Create** — OpenShift builds the image and deploys automatically |
| 9 | Go to **Networking → Routes** to find the auto-generated URL |

> **Build troubleshooting:** Go to **Builds → BuildConfigs** → click your build → **Logs** tab to watch the Docker build.

---

### 5B. Deploy Existing Image (no Git)

If you already have a Docker image in a registry (e.g. Docker Hub):

| Step | Action |
|------|--------|
| 1 | OpenShift Console → **+Add** → **Container Images** |
| 2 | **Image Name:** `yourdockerhub/ecifm-saml-bridge:latest` |
| 3 | **Application Name:** `ecifm-saml-bridge` |
| 4 | **Name:** `ecifm-saml-bridge` |
| 5 | **Container Port:** `8080` |
| 6 | Click **Advanced Options** → **Environment** → add the same env vars as 5A |
| 7 | Click **Create** |

---

### 5C. Apply YAML Manually (fine-grained control)

Import the `openshift/` manifests via the console **+** icon (top-right) in this order:

1. `openshift/configmap.yaml` — Environment variables
2. `openshift/deployment.yaml` — 2 replicas, health probes
3. `openshift/service.yaml` — ClusterIP on port 8080
4. `openshift/route.yaml` — Public HTTPS route (update hostname!)

> **Important**: Edit `openshift/configmap.yaml` first with your real `MAS_BASE_URL`, `JWT_ISSUER_URI` values.

---

### 5D. Configure a Custom Route (optional)

To use a friendly URL like `bridge.apps.ocp.example.com`:

| Step | Action |
|------|--------|
| 1 | OpenShift Console → **Networking → Routes** → **Create Route** |
| 2 | **Name:** `ecifm-saml-bridge` |
| 3 | **Hostname:** `bridge.apps.ocp.example.com` |
| 4 | **Service:** `ecifm-saml-bridge` |
| 5 | **Target Port:** `8080 → 8080` |
| 6 | **Security:** check _Secure Route_ → **Edge** TLS termination |
| 7 | Click **Create** |

---

### 5E. Verify

```powershell
# Check pods are running (OpenShift Console → Workloads → Pods)
oc get pods -w

# Get the public URL
oc get route ecifm-saml-bridge

# Test the endpoint
curl https://<route-host>/test
```

Expected: `ecifm-saml-bridge is running`

---

### Updating After Code Changes

When using **Import from Git (5A)**: push new code to the same branch — the BuildConfig automatically re-builds and re-deploys.

For manual updates via **Container Images (5B)**:

```powershell
mvn clean package -DskipTests
docker build -t ecifm-saml-bridge:0.1.0 .
docker push yourdockerhub/ecifm-saml-bridge:latest
oc rollout restart deployment/ecifm-saml-bridge
oc rollout status deployment/ecifm-saml-bridge
```

---

## API Endpoints

| Endpoint | Auth | Purpose |
|----------|------|---------|
| `GET /` | JWT | Redirect to `/redirect` |
| `GET /redirect` | JWT | Main SSO flow: extract JWT, sync groups, redirect to MAS |
| `GET /test` | None | Health check |
| `GET /actuator/health` | None | Kubernetes liveness/readiness probes |
| `GET /actuator/health/liveness` | None | Liveness probe |
| `GET /actuator/health/readiness` | None | Readiness probe |
| `GET /local/mock-sso` | None | Mock SSOConnect (dev only) |
| `GET /local/mock-redirect` | None | Mock redirect (dev only) |

---

## Key Design Decisions

1. **No WebSphere APIs** — Replaced `WSSubject.getCallerSubject()` with Spring Security `@AuthenticationPrincipal Jwt`
2. **No LTPA tokens** — Replaced `Cookie: LtpaToken2=...` with `Authorization: Bearer <jwt>`
3. **No XML config** — Spring Boot auto-configuration via `@Configuration` classes
4. **Microsoft Graph API fallback** — Handles Entra ID's 200-group JWT claim limit
5. **ConfigMap-based configuration** — All env vars injected via OpenShift ConfigMap
6. **IConnect plugin unchanged** — `SSOConnect.java` in the existing `ecifmSSOHelper` project stays as-is

---

## Health Checks

The deployment has two probes:

| Probe | Path | Delay | Period | Action |
|-------|------|-------|--------|--------|
| Liveness | `/actuator/health/liveness` | 60s | 30s | Restart pod if dead |
| Readiness | `/actuator/health/readiness` | 30s | 10s | Remove from Service if not ready |

---

## Related Documentation

- `MIGRATION_TO_TAS_OPENSHIFT.md` — Full migration plan (at parent directory level)
- `ECIFM_SAML_Technical_Design_Document.docx` — Technical design (legacy project)
- `ecifmSSOHelper_Project/docs/TECHNICAL_DESIGN.md` — IConnect plugin design (legacy project)
