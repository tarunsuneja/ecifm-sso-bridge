# ecifm-saml-bridge — OIDC IdP Deployment Guide

> **Version:** 0.2.0 — OIDC Identity Provider (IdP) architecture  
> **Date:** 2026-07-04  
> **Cluster:** NPOS2 (this doc)  
> **Target:** Any MAS/TRIRIGA environment on OpenShift

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Prerequisites](#2-prerequisites)
3. [Step 1 — Register an Entra ID App for the Bridge](#3-step-1--register-an-entra-id-app-for-the-bridge)
4. [Step 2 — Create the TLS Certificate](#4-step-2--create-the-tls-certificate)
5. [Step 3 — Generate the MAS OIDC Client Secret](#5-step-3--generate-the-mas-oidc-client-secret)
6. [Step 4 — Create OpenShift Secrets](#6-step-4--create-openshift-secrets)
7. [Step 5 — Create the ConfigMap](#7-step-5--create-the-configmap)
8. [Step 6 — Create the Route](#8-step-6--create-the-route)
9. [Step 7 — Create the Service and Deployment](#9-step-7--create-the-service-and-deployment)
10. [Step 8 — Build and Deploy](#10-step-8--build-and-deploy)
11. [Step 9 — Configure MAS Liberty OIDC Client (IDPCfg)](#11-step-9--configure-mas-liberty-oidc-client-idpcfg)
12. [Step 10 — Verify the Bridge Is Running](#12-step-10--verify-the-bridge-is-running)
13. [Step 11 — Test the Full Flow](#13-step-11--test-the-full-flow)
14. [Step 12 — Rollback Plan](#14-step-12--rollback-plan)
15. [Troubleshooting](#15-troubleshooting)
16. [Appendices](#16-appendices)

---

## 1. Architecture Overview

### 1.1 Problem Statement

Before this change, the bridge authenticated users with Entra ID (Azure AD) and then redirected them to MAS/TRIRIGA. However, MAS/Liberty required its own session — the user had to manually click "Microsoft" on the Liberty form login page to create a MAS session. This broke single-sign-on.

### 1.2 Solution: Bridge as OIDC IdP

The bridge is now a **trusted OIDC Identity Provider** for MAS/Liberty. MAS delegates authentication to the bridge, and the bridge proxies authentication to Entra ID. This matches exactly how TRIRIGA's OIDC SSO path works internally.

### 1.3 Component Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                        MAS Cluster (mas-inst1-core)              │
│                                                                  │
│  ┌──────────┐     ┌──────────┐     ┌──────────────────────┐     │
│  │ TRIRIGA  │────▶│ Liberty  │────▶│ CoreIDP (IDPCfg)    │     │
│  │ (SPA)    │     │ (OP)     │     │ - OIDC client        │     │
│  └──────────┘     │          │     │ - Truststore JKS     │     │
│                   │          │     └──────────┬───────────┘     │
│                   └──────────┘                │                 │
│                                               │ TLS to bridge   │
└───────────────────────────────────────────────┼─────────────────┘
                                                │
                    ┌───────────────────────────┘
                    ▼
        ┌──────────────────────────────────────┐
        │   ecifm-saml-bridge (bridge)          │
        │                                       │
        │  ┌──────────────────────────────┐     │
        │  │ SecurityConfig              │     │
        │  │  ├── Chain 1: AS endpoints  │     │
        │  │  ├── Chain 2: /local/*      │     │
        │  │  └── Chain 3: oauth2Login   │     │
        │  └──────────────────────────────┘     │
        │                                       │
        │  ┌──────────────────────────────┐     │
        │  │ AuthServerConfig             │     │
        │  │  ├── Client registration    │     │
        │  │  ├── JWK Source (RS256)     │     │
        │  │  └── Token customizer       │     │
        │  └──────────────────────────────┘     │
        │                                       │
        │  ┌──────────────────────────────┐     │
        │  │ Spring Security OAuth2       │     │
        │  │  ├── Entra ID as upstream   │     │
        │  │  └── OidcUser mapping       │     │
        │  └──────────────────────────────┘     │
        └──────────────────┬───────────────────┘
                           │
                           ▼
        ┌──────────────────────────────────────┐
        │   Microsoft Entra ID (Azure AD)      │
        │   Tenant: c99cc570-...               │
        │   App: cbcea157-...                  │
        └──────────────────────────────────────┘
```

### 1.4 Full Authentication Flow

```
Step 1:  User → TRIRIGA (main.facilities...)
Step 2:  TRIRIGA → Liberty OP (auth.inst1.../MaximoAppSuite)
         Liberty checks cookies, finds no mas-oidc=oidc session
Step 3:  Liberty → Bridge (ecifm-sso-bridge.../oauth2/authorize)
         ?client_id=mas-facilities
         &redirect_uri=https://auth.inst1.../oidcclient/redirect/default-oidc
         &response_type=code&scope=openid
Step 4:  Bridge (chain 1) → redirects to /oauth2/authorization/entra-id
Step 5:  Bridge (chain 3) → redirects to Entra ID login
         https://login.microsoftonline.com/{tenant}/oauth2/v2.0/authorize
Step 6:  User authenticates with Entra ID
Step 7:  Entra ID → redirects to bridge /login/oauth2/code/entra-id
         with auth code
Step 8:  Bridge exchanges code with Entra ID, gets OidcUser
Step 9:  Bridge redirects back to Liberty's redirect_uri
         https://auth.inst1.../oidcclient/redirect/default-oidc?code=xxx&state=yyy
Step 10: Liberty → Bridge /oauth2/token (client_secret_basic auth)
         exchanges code for ID token + access token
Step 11: Liberty validates ID token signature via bridge's JWKS endpoint
         extracts preferred_username → sets UserPrincipal
Step 12: Liberty → TRIRIGA with authenticated user
```

### 1.5 OIDC Token Claims

The bridge's ID token includes the following claims (customized from Entra ID's OidcUser):

| Claim | Source | Example |
|-------|--------|---------|
| `sub` | Entra ID `sub` | `a1b2c3d4-e5f6-7890-abcd-ef1234567890` |
| `preferred_username` | Entra ID email or `preferred_username` | `tarun.suneja@ecifm.com` |
| `email` | Entra ID `email` | `tarun.suneja@ecifm.com` |
| `name` | Entra ID `name` or `preferred_username` | `Tarun Suneja` |

Liberty maps `preferred_username` to the `UserPrincipal`, which TRIRIGA's `AuthenticationHandler.getUserIdFromRequest()` reads to auto-create the user session.

### 1.6 Key Differences from Old Architecture

| Aspect | Old (v0.1.0) | New (v0.2.0) |
|--------|-------------|-------------|
| Bridge role | Client-only: called MAS SSOConnect | OIDC Identity Provider (IdP) |
| Auth flow | Bridge auth → redirect MAS → user clicks Microsoft | Full OIDC flow: MAS → Bridge → Entra ID → Bridge → MAS |
| User action required | Must click "Microsoft" button on Liberty form login | Zero clicks |
| Security | Bearer token validation | OIDC Authorization Code flow + RS256 signed ID tokens |
| Token format | JWT from MAS SSO | ID token signed by bridge's RSA key |

### 1.7 Key Architecture Discoveries

During implementation, these critical insights were discovered:

1. **Internal cluster TLS**: From inside the cluster, the OpenShift route presents a **self-signed ingress-operator certificate**, NOT the Let's Encrypt certificate visible from outside. Any frontend that terminates TLS behind the route replaces the external cert.

2. **Java PKIX and self-signed certs**: Java PKIX validation rejects self-signed certs even when added to the truststore as a `trustedCertEntry`. The signature of a self-signed cert cannot be verified against itself in the context of path validation. A proper CA → leaf cert chain is required.

3. **Liberty OIDC client redirect URI**: Liberty's OIDC client (`default-oidc`) sends its **own** callback URL (`auth.inst1.../oidcclient/redirect/default-oidc`) as the `redirect_uri` parameter in the `/oauth2/authorize` request to the bridge. This is the Liberty OP's own callback endpoint — NOT the TRIRIGA application redirect. The bridge's OAuth2 Authorization Server must register **both** redirect URIs:
   - TRIRIGA's callback: `main.facilities.../oidcclient/redirect/facilities`
   - Liberty's callback: `auth.inst1.../oidcclient/redirect/default-oidc`

4. **Service CA SAN limitation**: Annotating the bridge service with `service.beta.openshift.io/serving-cert-secret-name` injects a service-serving certificate, but its SAN only contains `*.svc` DNS names — it will never match a route hostname.

5. **All 3 MAS apps share one CoreIDP**: MAS routes TRIRIGA, Maximo Assist, and Monitor through the same CoreIDP (`mas-inst1-core`). Changing the `IDPCfg` resource shifts authentication for **all** applications simultaneously.

---

## 2. Prerequisites

### 2.1 Tools

| Tool | Version | Purpose |
|------|---------|---------|
| JDK | 17+ | Compile the bridge JAR |
| Maven | 3.9+ | Build the JAR |
| Docker / Podman | latest | Build container image |
| OpenShift CLI (`oc`) | 4.x | Deploy to OpenShift |
| OpenShift cluster access | admin | Create namespaces, secrets, routes |
| MAS Admin UI access | admin | Configure OIDC provider |
| OpenSSL | any | Generate certificates and secrets |

### 2.2 Information to Gather

Before starting, collect these environment-specific values:

| Item | Example | Where to Find |
|------|---------|---------------|
| OpenShift cluster API URL | `https://api.npos2.ecifmdev.net:6443` | Cluster admin |
| OpenShift ingress domain | `apps.npos2.ecifmdev.net` | Cluster admin |
| MAS instance namespace | `mas-inst1-core` | MAS installation |
| MAS domain | `inst1.apps.npos2.ecifmdev.net` | MAS installation |
| Liberty OP hostname | `auth.inst1.apps.npos2.ecifmdev.net` | OpenShift route in MAS namespace |
| TRIRIGA route hostname | `main.facilities.inst1.apps.npos2.ecifmdev.net` | OpenShift route |
| MAS admin credentials | `maxadmin` | MAS installation |
| Entra ID tenant ID | `c99cc570-ba4f-474e-897d-22255a3cecd7` | Azure AD overview page |
| Truststore password | `L4i5gKLuCxC4iEhc` | From MAS secret or existing IDPCfg |

### 2.3 NPOS2 Environment URLs

| Component | URL |
|-----------|-----|
| Bridge | `https://ecifm-sso-bridge-ecifm-sso-bridge.apps.npos2.ecifmdev.net` |
| TRIRIGA | `https://main.facilities.inst1.apps.npos2.ecifmdev.net/app/tririga` |
| MAS Liberty auth | `https://auth.inst1.apps.npos2.ecifmdev.net` |
| TRIRIGA OIDC callback | `https://main.facilities.inst1.apps.npos2.ecifmdev.net/oidcclient/redirect/facilities` |
| Liberty OIDC callback | `https://auth.inst1.apps.npos2.ecifmdev.net/oidcclient/redirect/default-oidc` |
| Entra ID tenant | `c99cc570-ba4f-474e-897d-22255a3cecd7` |
| Azure AD app client ID | `cbcea157-2c35-4ce3-b86c-782282e00857` |

---

## 3. Step 1 — Register an Entra ID App for the Bridge

If using an existing Entra ID app (like TRIRIGA's existing registration), skip to [§3.2](#32-verify-redirect-uri).

### 3.1 Create a New App Registration

1. Go to **Azure Portal → Microsoft Entra ID → App registrations → New registration**
2. Name: `ecifm-saml-bridge-<env>` (e.g., `ecifm-saml-bridge-prod`)
3. Supported account types: **Accounts in this organizational directory only** (single tenant)
4. Redirect URI (Web): `https://<bridge-route-hostname>/login/oauth2/code/entra-id`
   - If you don't know the hostname yet, skip this and add it later
5. Click **Register**
6. Note the **Application (client) ID** and **Directory (tenant) ID**

### 3.2 Verify Redirect URI

Whether creating new or using existing:

1. In the app registration, select **Authentication** (left menu)
2. Under **Web → Redirect URIs**, ensure this is present:

   ```
   https://<bridge-route-hostname>/login/oauth2/code/entra-id
   ```

3. If missing, click **Add URI**, paste the URL, and **Save**

### 3.3 Create/Verify Client Secret

1. Select **Certificates & secrets → Client secrets**
2. Click **New client secret**
   - Description: `ecifm-saml-bridge`
   - Expires: 12 or 24 months
3. **Copy the secret value immediately** — it will not be shown again

### 3.4 API Permissions (Optional)

If using Microsoft Graph API fallback (for JWT tokens with >200 groups):

1. Select **API permissions**
2. Add delegated permissions:
   - `Microsoft Graph / GroupMember.Read.All`
   - `Microsoft Graph / User.Read`
3. Click **Grant admin consent**

---

## 4. Step 2 — Create the TLS Certificate

**Critical requirement**: The certificate presented by the bridge route must be trusted by MAS/Liberty's Java truststore. You **cannot** use a self-signed cert — Java PKIX validation requires a proper CA chain.

### Option A: Internal CA (Recommended for Dev/Test)

This approach works in any cluster without external dependencies.

#### 4a.1 Generate the Internal CA (one-time per environment)

```bash
# Generate CA private key
openssl genrsa -out internal-ca.key 4096

# Self-sign the CA certificate (10-year validity)
openssl req -x509 -new -nodes -key internal-ca.key -sha256 -days 3650 \
  -out internal-ca.crt \
  -subj "/CN=<ENV>-Internal-CA"

# Example:
# openssl req -x509 -new -nodes -key internal-ca.key -sha256 -days 3650 \
#   -out internal-ca.crt \
#   -subj "/CN=NPOS2-Internal-CA"
```

#### 4a.2 Generate the Bridge TLS Certificate

```bash
# Generate bridge private key
openssl genrsa -out bridge-tls.key 2048

# Create CSR
openssl req -new -key bridge-tls.key -out bridge-tls.csr \
  -subj "/CN=<bridge-route-hostname>"

# Create cert config with SAN
cat > bridge-tls.ext <<EOF
authorityKeyIdentifier=keyid,issuer
basicConstraints=CA:FALSE
keyUsage=digitalSignature,nonRepudiation,keyEncipherment,dataEncipherment
subjectAltName=DNS:<bridge-route-hostname>
EOF

# Sign the cert with the internal CA
openssl x509 -req -in bridge-tls.csr -CA internal-ca.crt -CAkey internal-ca.key \
  -CAcreateserial -out bridge-tls.crt -days 365 -sha256 -extfile bridge-tls.ext
```

#### 4a.3 Create the Full Chain

```bash
# Chain = bridge cert + CA cert (in that order)
cat bridge-tls.crt internal-ca.crt > bridge-tls-chain.crt
```

#### 4a.4 Create the TLS Secret

```bash
oc create secret tls bridge-tls-secret -n ecifm-sso-bridge \
  --cert=bridge-tls-chain.crt --key=bridge-tls.key
```

**Keep `internal-ca.crt`** — you will need it for the IDPCfg certificates section (Step 9).

### Option B: Let's Encrypt (with cert-manager)

If the cluster has cert-manager and the domain is public:

```yaml
apiVersion: cert-manager.io/v1
kind: Certificate
metadata:
  name: ecifm-sso-bridge-tls
  namespace: ecifm-sso-bridge
spec:
  secretName: bridge-tls-secret
  issuerRef:
    name: letsencrypt-prod
    kind: ClusterIssuer
  dnsNames:
  - <bridge-route-hostname>
```

### Option C: OpenShift Default Ingress Cert

Only use this if you know the cluster's default ingress CA cert and can add it to the MAS truststore. This is generally not recommended because:
1. The ingress CA cert varies by cluster
2. Future cluster upgrades may rotate the CA

---

## 5. Step 3 — Generate the MAS OIDC Client Secret

This is the shared secret between the bridge and MAS Liberty. It must be the **exact same value** in both places.

```bash
openssl rand -base64 32
```

**Example output:** `fMkWmZx8qZ8d9R/f+40lWrZJlTCzpwprxGqoWxXURU4=`

This value will be used in:
1. The bridge's `ecifm-bridge-secrets` secret (Step 4)
2. The Liberty OIDC client credentials secret (Step 9)

---

## 6. Step 4 — Create OpenShift Secrets

### 6.1 Create the Namespace

```bash
oc new-project ecifm-sso-bridge
```

### 6.2 Create Azure AD Credentials Secret

```bash
oc create secret generic azure-ad-credentials -n ecifm-sso-bridge \
  --from-literal=AZURE_CLIENT_ID=<app-client-id> \
  --from-literal=AZURE_CLIENT_SECRET=<app-client-secret> \
  --from-literal=JWT_ISSUER_URI=https://login.microsoftonline.com/<tenant-id>/v2.0
```

### 6.3 Create Bridge OIDC Client Secret

```bash
oc create secret generic ecifm-bridge-secrets -n ecifm-sso-bridge \
  --from-literal=MAS_OIDC_CLIENT_SECRET=<secret-from-step-3>
```

If you also use the TRIRIGA REST API or Graph API, add:

```bash
oc create secret generic ecifm-bridge-secrets -n ecifm-sso-bridge \
  --from-literal=MAS_OIDC_CLIENT_SECRET=<secret-from-step-3> \
  --from-literal=AZURE_CLIENT_SECRET=<azure-client-secret> \
  --from-literal=TRIRIGA_PASSWORD=<tririga-service-password>
```

---

## 7. Step 5 — Create the ConfigMap

Create `openshift/configmap.yaml`:

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: ecifm-bridge-config
  namespace: ecifm-sso-bridge
data:
  # Bridge OIDC issuer (must match route hostname exactly)
  BRIDGE_ISSUER_URL: "https://<bridge-route-hostname>"

  # MAS base URL (TRIRIGA domain)
  MAS_BASE_URL: "https://<mas-domain>"

  # TRIRIGA context path
  MAS_CONTEXT: "/tririga"

  # Post-login redirect URL in TRIRIGA
  MAS_REDIRECT_URL: "https://<mas-domain>/app/tririga"

  # SSOConnect REST endpoint for group sync
  MAS_REST_API: "/html/en/default/rest/SSOConnect?userName={0}&adGroupName={1}"

  # MAS OIDC client configuration
  MAS_OIDC_CLIENT_ID: "mas-facilities"
  MAS_OIDC_REDIRECT_URI: "https://<mas-domain>/oidcclient/redirect/facilities"

  # Liberty OIDC client callback (the redirect_uri that Liberty sends to the bridge)
  MAS_OAUTH_CLIENT_REDIRECT_URI: "https://<liberty-op-hostname>/oidcclient/redirect/default-oidc"

  # Entra ID issuer
  JWT_ISSUER_URI: "https://login.microsoftonline.com/<tenant-id>/v2.0"

  # Entra ID app client ID
  AZURE_CLIENT_ID: "<app-client-id>"

  # TRIRIGA service account (for SOAP calls)
  TRIRIGA_USERNAME: "<service-account-email>"

  # Enable Microsoft Graph API fallback
  GRAPH_API_ENABLED: "true"

  # Spring profile
  SPRING_PROFILES_ACTIVE: "openshift"
```

Apply:

```bash
oc apply -f openshift/configmap.yaml
```

---

## 8. Step 6 — Create the Route

Create `openshift/route.yaml`:

```yaml
apiVersion: route.openshift.io/v1
kind: Route
metadata:
  name: ecifm-sso-bridge
  namespace: ecifm-sso-bridge
spec:
  host: <bridge-route-hostname>
  port:
    targetPort: 8080
  tls:
    termination: reencrypt
    # Reference the TLS secret created in Step 2
    key: <contents of bridge-tls.key>
    certificate: <contents of bridge-tls-chain.crt>
    # OR reference the secret directly:
  to:
    kind: Service
    name: ecifm-sso-bridge
    weight: 100
  wildcardPolicy: None
```

**Important**: With `termination: reencrypt` + the custom cert, OpenShift terminates TLS at the route edge using your cert, then re-encrypts to the pod. If you don't set `destinationCACertificate`, OpenShift re-encrypts using the cluster's service CA (which has `*.svc` SANs only). To fix this, either:

1. Set `destinationCACertificate` to your internal CA cert, OR
2. Use `termination: passthrough` (TLS goes straight to the pod)

For simplicity with internal CA, use `passthrough`:

```yaml
  tls:
    termination: passthrough
```

---

## 9. Step 7 — Create the Service and Deployment

### 9.1 Service

Create `openshift/service.yaml`:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: ecifm-sso-bridge
  namespace: ecifm-sso-bridge
spec:
  ports:
  - name: 8080-tcp
    port: 8080
    protocol: TCP
    targetPort: 8080
  selector:
    app: ecifm-sso-bridge
  type: ClusterIP
```

### 9.2 Deployment

Create `openshift/deployment.yaml`:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ecifm-sso-bridge
  namespace: ecifm-sso-bridge
spec:
  replicas: 1
  selector:
    matchLabels:
      app: ecifm-sso-bridge
  template:
    metadata:
      labels:
        app: ecifm-sso-bridge
    spec:
      containers:
      - name: bridge
        image: image-registry.openshift-image-registry.svc:5000/ecifm-sso-bridge/ecifm-sso-bridge:latest
        ports:
        - containerPort: 8080
        envFrom:
        - configMapRef:
            name: ecifm-bridge-config
        - secretRef:
            name: azure-ad-credentials
        - secretRef:
            name: ecifm-bridge-secrets
        livenessProbe:
          httpGet:
            path: /actuator/health/liveness
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 8080
          initialDelaySeconds: 20
          periodSeconds: 5
```

---

## 10. Step 8 — Build and Deploy

### Option A: OpenShift Build (Source-to-Image)

```bash
# From the project root directory
oc new-build --name ecifm-sso-bridge \
  --image-stream=java:17 \
  --binary=true \
  -n ecifm-sso-bridge

oc start-build ecifm-sso-bridge --from-dir=. --wait -n ecifm-sso-bridge

# Tag for traceability
oc tag ecifm-sso-bridge:latest ecifm-sso-bridge:v0.2.0 -n ecifm-sso-bridge
```

### Option B: Docker Build (Local)

```bash
docker build -t ecifm-sso-bridge:v0.2.0 .

# Push to registry
docker tag ecifm-sso-bridge:v0.2.0 <registry>/ecifm-sso-bridge:v0.2.0
docker push <registry>/ecifm-sso-bridge:v0.2.0
```

### Option C: Maven + Docker (Windows Dev Machine)

```powershell
# 1. Build JAR
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
mvn clean package -DskipTests

# 2. Build Docker image
docker build -t ecifm-saml-bridge:0.2.0 .

# 3. Tag for OpenShift internal registry
oc registry login
docker tag ecifm-saml-bridge:0.2.0 image-registry.openshift-image-registry.svc:5000/ecifm-sso-bridge/ecifm-sso-bridge:latest
docker push image-registry.openshift-image-registry.svc:5000/ecifm-sso-bridge/ecifm-sso-bridge:latest
```

### 10.1 Deploy

```bash
oc apply -f openshift/service.yaml -n ecifm-sso-bridge
oc apply -f openshift/route.yaml -n ecifm-sso-bridge
oc apply -f openshift/deployment.yaml -n ecifm-sso-bridge

# Watch rollout
oc rollout status deployment/ecifm-sso-bridge -n ecifm-sso-bridge -w
```

---

## 11. Step 9 — Configure MAS Liberty OIDC Client (IDPCfg)

This is the **most critical step**. You configure MAS's CoreIDP to trust the bridge as an OIDC provider.

### 11.1 Locate the IDPCfg resource

```bash
# Find the correct namespace (usually mas-<instance>-core)
oc get idpcfg -n mas-inst1-core

# Save current config for rollback
oc get idpcfg <name> -n mas-inst1-core -o yaml > idpcfg-current-backup.yaml
```

### 11.2 Create the OIDC Client Credentials Secret

MAS/Liberty needs a Kubernetes Secret containing the OIDC client credentials for the bridge.

```bash
oc create secret generic -n mas-inst1-core \
  inst1-usersupplied-oidc-bridge-creds-system \
  --from-literal=clientId=mas-facilities \
  --from-literal=clientSecret=<secret-from-step-3>
```

**CRITICAL**: The `clientSecret` value here must be **exactly the same** as the `MAS_OIDC_CLIENT_SECRET` in the bridge's `ecifm-bridge-secrets` secret. If they don't match, the token exchange at `/oauth2/token` fails with `client_secret does not match`.

### 11.3 Update the IDPCfg

The IDPCfg resource has an `oidc` section. If it doesn't exist yet, you need to add it. If there's a `samlIdp` section being replaced, remove or comment it out.

Create `idpcfg-updated.yaml`:

```yaml
apiVersion: authentication.mas.ibm.com/v1
kind: IDPCfg
metadata:
  name: <idpcfg-name>
  namespace: mas-inst1-core
spec:
  # ... keep existing spec fields (authCache, etc.) ...

  oidc:
    discoveryEndpoint: "https://<bridge-route-hostname>/.well-known/openid-configuration"
    authority: "https://<bridge-route-hostname>"
    clientId: "mas-facilities"
    clientSecret:
      secretName: "inst1-usersupplied-oidc-bridge-creds-system"
    clockSkew: 300
    issuerIdentifier: "https://<bridge-route-hostname>"

    # The CA cert that signed the bridge's TLS certificate
    # (from Step 2 - Option A: internal-ca.crt)
    certificates:
      - alias: "<env>-Internal-CA"
        certificate: |
          -----BEGIN CERTIFICATE-----
          ... contents of internal-ca.crt ...
          -----END CERTIFICATE-----

    # REQUIRED: Prevents infinite redirect loop
    authFilter: "mas-oidc=oidc"
```

**Key fields explained:**

| Field | Value | Purpose |
|-------|-------|---------|
| `discoveryEndpoint` | Bridge's OIDC discovery URL | Liberty fetches OIDC metadata from here |
| `authority` | Bridge issuer URL | Used for token validation |
| `clientId` | `mas-facilities` | Must match `MAS_OIDC_CLIENT_ID` in bridge ConfigMap |
| `clientSecret.secretName` | Name of the K8s secret | The Secret created in step 11.2 |
| `clockSkew` | `300` seconds | Allows 5-minute clock skew between bridge and MAS |
| `issuerIdentifier` | Bridge issuer URL | Validates the `iss` claim in ID tokens |
| `certificates` | TLS CA certificate | Added to Liberty's JKS truststore so TLS to bridge succeeds |
| `authFilter` | `mas-oidc=oidc` | Cookie-based filter: only redirect to bridge if this cookie is present |

### 11.4 Apply the IDPCfg

```bash
oc apply -f idpcfg-updated.yaml -n mas-inst1-core
```

### 11.5 Verify the Configuration was Applied

After applying, two things happen asynchronously:

#### 11.5.1 Truststore Worker

The operator's truststore worker picks up the change and adds the CA cert to Liberty's JKS truststore:

```bash
oc logs -n mas-inst1-core -l app.kubernetes.io/name=ibm-mas-operator --tail=50 | grep -i truststore
```

Expected output:
```
Processing truststore update...
Adding certificate <env>-Internal-CA to truststore...
Truststore update completed.
```

To verify the cert was added:

```bash
# Exec into a MAS pod to check the truststore
oc exec -n mas-inst1-core <coreidp-pod> -- keytool -list -keystore /etc/mas/certs/truststore/truststore.jks \
  -storepass <truststore-password> | grep -i internal
```

Expected: `ecifm-internal-ca, <date>, trustedCertEntry`

#### 11.5.2 CoreIDP Restart

The CoreIDP pod restarts with the new OIDC client config. Watch for these success messages:

```bash
oc logs -n mas-inst1-core -l app.ibm.com/app=coreidp --tail=100 -f
```

Look for:
```
CWWKS1526I: The OpenID Connect client [default-oidc] configuration has been established.
CWWKS1700I: OpenID Connect client default-oidc configuration successfully processed.
```

These confirm:
- The bridge's OIDC discovery endpoint was reachable from Liberty
- The TLS handshake succeeded (truststore has the CA cert)
- The OIDC client was configured with the bridge's metadata

If you see `CWPKI0823E: SSL handshake failed` or `CWPKI0020E: SSL error`, the TLS certificate chain is not trusted. Check:
1. The CA cert in IDPCfg `certificates` matches the CA that signed the bridge cert
2. The bridge route presents the full chain (bridge cert + CA cert)

### 11.6 How Liberty Was Configured (Reference)

In our NPOS2 implementation, Liberty's OIDC client config is at `/tmp/writeable/opt/was/liberty/wlp/usr/oidc/oidc.xml` inside the CoreIDP pod:

```xml
<oidc:client id="default-oidc"
  clientId="mas-facilities"
  clientSecret="fMkWmZx8qZ8d9R/f+40lWrZJlTCzpwprxGqoWxXURU4="
  displayName="Entra AD OIDC Bridge"
  scope="openid profile email"
  signAlg="RS256"
  tokenEndpointAuthMethod="client_secret_basic"
  httpsRequired="true"
  realmName="oidc"
  redirectToRPHostAndPort="auth.inst1.apps.npos2.ecifmdev.net"
  redirectToRPPortContextPath="/oidcclient/redirect/default-oidc"
  authFilterRequestUrl="https://main.facilities.inst1.apps.npos2.ecifmdev.net/oidcclient/redirect/facilities"
  authFilterId="mas-oidc">
  <authFilter id="mas-oidc">
    <requestUrl id="mas-oidc-url"
      urlPattern="https://main.facilities.inst1.apps.npos2.ecifmdev.net/oidcclient/redirect/facilities"
      matchType="contains"/>
    <cookie id="mas-oidc-cookie"
      name="mas-oidc"
      value="oidc"
      matchType="equals"
      required="false"/>
  </authFilter>
</oidc:client>
```

---

## 12. Step 10 — Verify the Bridge Is Running

### 12.1 Basic Health Check

```bash
curl -k https://<bridge-route-hostname>/actuator/health
# Expected: {"status":"UP"}
```

### 12.2 OIDC Discovery Endpoint

```bash
curl -k https://<bridge-route-hostname>/.well-known/openid-configuration
```

Expected JSON:

```json
{
  "issuer": "https://<bridge-route-hostname>",
  "authorization_endpoint": "https://<bridge-route-hostname>/oauth2/authorize",
  "token_endpoint": "https://<bridge-route-hostname>/oauth2/token",
  "jwks_uri": "https://<bridge-route-hostname>/oauth2/jwks",
  "scopes_supported": ["openid", "profile", "email"],
  "response_types_supported": ["code"],
  "grant_types_supported": ["authorization_code"],
  "subject_types_supported": ["public"],
  "id_token_signing_alg_values_supported": ["RS256"]
}
```

### 12.3 JWKS Endpoint

```bash
curl -k https://<bridge-route-hostname>/oauth2/jwks
```

Expected:

```json
{
  "keys": [{
    "kty": "RSA",
    "use": "sig",
    "alg": "RS256",
    "kid": "...",
    "n": "...",
    "e": "AQAB"
  }]
}
```

### 12.4 Check Pod Logs

```bash
oc logs -n ecifm-sso-bridge -l app=ecifm-sso-bridge --tail=30
```

Look for startup markers:

```
=== OAuth2 Configuration ===
AZURE_CLIENT_ID: <app-client-id>
AZURE_CLIENT_SECRET length: 40
JWT_ISSUER_URI: https://login.microsoftonline.com/<tenant-id>/v2.0
BRIDGE_ISSUER_URL: https://<bridge-route-hostname>
MAS_OIDC_CLIENT_ID: mas-facilities
MAS_OIDC_REDIRECT_URI: https://<mas-domain>/oidcclient/redirect/facilities
=============================
Registering OIDC client: mas-facilities with TRIRIGA redirect: ... and OAuth client redirect: ...
Generating RSA key pair for OIDC token signing
```

### 12.5 Verify from Inside the Cluster

Exec into any pod in the same cluster and test TLS:

```bash
oc exec <any-pod> -- curl -v https://<bridge-route-hostname>/.well-known/openid-configuration
```

Check that the TLS handshake succeeds (no certificate errors).

---

## 13. Step 11 — Test the Full Flow

### 13.1 First-Time Test (Incognito Window)

1. Open an **incognito/private** browser window
2. Navigate to: `https://<mas-domain>/app/tririga`
3. Observe the redirect chain:
   - TRIRIGA SPA loads
   - Redirected to Liberty auth server
   - Liberty redirects to bridge (`/oauth2/authorize`)
   - Bridge redirects to Entra ID (`login.microsoftonline.com`)
   - **Enter your credentials** (first time only)
   - Redirected back to bridge, then to Liberty, then to TRIRIGA
4. **Result:** You land on the TRIRIGA home page

### 13.2 Subsequent Tests (Should Be Zero Clicks)

1. Open another incognito window
2. Navigate to TRIRIGA
3. **Result:** Zero clicks — you land directly on the TRIRIGA home page

### 13.3 Verify in Bridge Logs

```bash
oc logs -n ecifm-sso-bridge -l app=ecifm-sso-bridge --tail=50 -f
```

Expected log sequence during a login:

```
Saved request https://<bridge>/oauth2/authorize?... to session
Redirecting to https://<bridge>/oauth2/authorization/entra-id
Redirecting to https://login.microsoftonline.com/...
Retrieved SecurityContextImpl [Authentication=OAuth2AuthenticationToken [...preferred_username=user@domain...]]
Retrieved registered client
Validated authorization code request parameters
Generated authorization code
Saved authorization
Redirecting to https://<liberty-op>/oidcclient/redirect/default-oidc?code=...
Client authentication failed: [invalid_client] ... (if secret mismatch)
```

---

## 14. Step 12 — Rollback Plan

To revert to the previous authentication method (e.g., direct Entra AD):

```bash
# 1. Restore the original IDPCfg from backup
oc apply -f idpcfg-backup.yaml -n mas-inst1-core

# 2. Scale down the bridge (optional, stop it if not needed)
oc scale deployment ecifm-sso-bridge --replicas=0 -n ecifm-sso-bridge

# 3. Force CoreIDP pod restart to pick up the old config
oc delete pod -n mas-inst1-core -l app.ibm.com/app=coreidp

# 4. Wait for CoreIDP to come back online
oc wait --for=condition=ready pod -n mas-inst1-core -l app.ibm.com/app=coreidp --timeout=300s
```

---

## 15. Troubleshooting

### 15.1 `CWPKI0823E: SSL handshake failed` / PKIX Path Validation Failed

**Symptom:** Liberty returns TLS errors when connecting to the bridge.

**Causes:**
- Missing or incorrect CA cert in IDPCfg `certificates`
- Bridge route not presenting the full cert chain
- Self-signed cert used (Java PKIX rejects self-signed certs even when added as trustedCertEntry)

**Solutions:**
1. Verify the bridge route presents the full chain (`bridge-tls-chain.crt`)
2. Verify the CA cert in IDPCfg matches the CA that signed the bridge cert
3. From a MAS pod, test TLS: `oc exec <pod> -- curl -v https://<bridge-hostname>`
4. Check the truststore: `keytool -list -keystore /etc/mas/certs/truststore/truststore.jks`

### 15.2 `client_secret does not match`

**Symptom:** Token exchange fails at `/oauth2/token`.

**Cause:** The client secret in the bridge's `ecifm-bridge-secrets` doesn't match the one in `inst1-usersupplied-oidc-bridge-creds-system`.

**Solution:** Patch the bridge secret to match Liberty's:

```bash
# Get the correct secret from Liberty's secret
$LIBERTY_SECRET=$(oc get secret -n mas-inst1-core inst1-usersupplied-oidc-bridge-creds-system -o json | jq -r '.data.clientSecret' | base64 -d)

# Encode it for the bridge secret
$ENCODED=$(echo -n "$LIBERTY_SECRET" | base64 -w 0)

# Patch the bridge secret
oc patch secret -n ecifm-sso-bridge ecifm-bridge-secrets --type merge \
  --patch "{\"data\":{\"MAS_OIDC_CLIENT_SECRET\":\"$ENCODED\"}}"

# Restart the bridge
oc rollout restart deployment/ecifm-sso-bridge -n ecifm-sso-bridge
```

### 15.3 `redirect_uri` not registered

**Symptom:** Bridge returns HTTP 400 after Entra ID auth with redirect_uri error.

**Cause:** The bridge's OAuth2 Authorization Server doesn't recognize the redirect URI that Liberty sends.

**Solution:** The bridge needs both redirect URIs registered:
1. TRIRIGA's callback: `https://<mas-domain>/oidcclient/redirect/facilities`
2. Liberty's callback: `https://<liberty-op>/oidcclient/redirect/default-oidc`

Set the `MAS_OAUTH_CLIENT_REDIRECT_URI` env var in the bridge ConfigMap for the second URI.

### 15.4 Infinite Redirect Loop

**Symptom:** Browser bounces between bridge and Entra ID without landing on TRIRIGA.

**Cause:** Missing `authFilter` in IDPCfg. Without it, Liberty sends all unauthenticated requests to the bridge, and the bridge always redirects to Entra ID.

**Solution:** Ensure `authFilter: "mas-oidc=oidc"` is set in the IDPCfg OIDC section.

### 15.5 Ephemeral RSA Keys

**Symptom:** After a rolling update, users who had active tokens get validation errors.

**Cause:** The bridge generates a new RSA key pair on each startup.

**Workaround:** Run 1 replica (`replicas: 1`).

**Long-term fix:** Implement persistent JWK Set storage (see Appendix C).

### 15.6 Debug Logging

The bridge has verbose logging. To increase:

```bash
oc set env deployment/ecifm-sso-bridge LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_SECURITY=TRACE
oc logs -n ecifm-sso-bridge -l app=ecifm-sso-bridge -f
```

---

## 16. Appendices

### Appendix A — OpenShift CLI Quick Reference

```bash
# Login
oc login --token=<token> --server=https://api.<cluster>:6443

# Switch project
oc project ecifm-sso-bridge

# Apply all resources
oc apply -f openshift/configmap.yaml
oc apply -f openshift/secret.yaml
oc apply -f openshift/service.yaml
oc apply -f openshift/route.yaml
oc apply -f openshift/deployment.yaml

# Restart
oc rollout restart deployment/ecifm-sso-bridge

# Watch rollout
oc rollout status deployment/ecifm-sso-bridge -w

# View logs
oc logs -l app=ecifm-sso-bridge --tail=50

# Scale
oc scale deployment/ecifm-sso-bridge --replicas=1

# Exec into pod
oc exec -it <pod-name> -- /bin/sh

# Port forward (for local testing)
oc port-forward deployment/ecifm-sso-bridge 8080:8080
```

### Appendix B — Git Tags and Commits

| Tag | Description |
|-----|-------------|
| `v0.1.0-bridge-client-only` | Original bridge code (SAML client, no OIDC IdP) |
| `v0.2.0-oidc-idp` | OIDC IdP implementation |

**Changed files in v0.2.0:**

```
M  openshift/configmap.yaml
M  openshift/deployment.yaml
M  openshift/secret.yaml
M  openshift/route.yaml        (if route was modified)
M  pom.xml
A  src/main/java/com/ecifm/saml/bridge/config/AuthServerConfig.java
M  src/main/java/com/ecifm/saml/bridge/config/SecurityConfig.java
M  src/main/java/com/ecifm/saml/bridge/EcifmSamlBridgeApplication.java
M  src/main/resources/application-openshift.yml
A  docs/DEPLOYMENT.md
A  docs/ROLLBACK.md
A  docs/SESSION_HISTORY.md
A  docs/resources/             (snapshots, certs, etc.)
```

### Appendix C — Persistent JWK Set

**Current behavior:** The bridge generates a new RSA 2048-bit key pair at startup. Each pod restart = new keys.

**Why this matters:** With 2+ replicas, each pod has a different key. If pod A signs a token and pod B receives the token request, Liberty's JWKS fetch may get pod B's key and fail to validate pod A's signature.

**Recommended production approach:** Store the JWK Set in a file that persists across restarts (e.g., a PVC or ConfigMap):

1. Generate a JWK Set offline:
   ```java
   RSAKey rsaKey = new RSAKeyGenerator(2048)
       .keyID("bridge-1")
       .algorithm(JWSAlgorithm.RS256)
       .generate();
   JWKSet jwkSet = new JWKSet(rsaKey);
   String json = jwkSet.toString();
   ```

2. Store the JSON in a ConfigMap or Secret named `ecifm-bridge-jwks`

3. Mount the file in the pod at `/etc/jwks/jwks.json`

4. Modify `AuthServerConfig.java` to load from file if present:
   ```java
   @Value("${bridge.jwks.file:}")
   private String jwksFilePath;

   @Bean
   public JWKSource<SecurityContext> jwkSource() throws Exception {
       if (jwksFilePath != null && !jwksFilePath.isEmpty()) {
           try (var reader = new java.io.FileReader(jwksFilePath)) {
               JWKSet jwkSet = JWKSet.load(reader);
               return new ImmutableJWKSet<>(jwkSet);
           }
       }
       // Fall back to generated key
       ...
   }
   ```

### Appendix D — Code Reference: `AuthServerConfig.java`

This class configures the OIDC Authorization Server:

- **`registeredClientRepository()`** — Registers `mas-facilities` as a trusted OIDC client with dual redirect URIs (TRIRIGA + Liberty). Uses `{noop}` prefix for the client secret (handled by `DelegatingPasswordEncoder`).

- **`jwkSource()`** — Generates an RSA 2048-bit key pair and exposes it via JWKS endpoint (`/oauth2/jwks`). Liberty fetches this to validate ID token signatures.

- **`tokenCustomizer()`** — Injects claims from the Entra ID `OidcUser` into the bridge's ID token. Maps `sub`, `preferred_username`, `email`, `name`.

### Appendix E — Code Reference: `SecurityConfig.java`

Three security filter chains:

| Order | Matcher | Purpose | Auth mechanism |
|-------|---------|---------|----------------|
| `@Order(1)` | AS endpoints (`/oauth2/*`, `/.well-known/*`) | OIDC Authorization Server | Delegates to Entra ID via `LoginUrlAuthenticationEntryPoint` |
| `@Order(2)` | `/local/**` | Local test endpoints | Permit all |
| `@Order(3)` | All other requests | OAuth2 client for Entra ID | `oauth2Login` → Entra ID |

### Appendix F — Environment Variables Reference

| Variable | Required | Source | Purpose |
|----------|----------|--------|---------|
| `AZURE_CLIENT_ID` | Yes | ConfigMap | Entra ID app client ID |
| `AZURE_CLIENT_SECRET` | Yes | Secret | Entra ID app client secret |
| `JWT_ISSUER_URI` | Yes | ConfigMap | Entra ID v2.0 issuer URL |
| `MAS_BASE_URL` | Yes | ConfigMap | TRIRIGA base URL |
| `MAS_CONTEXT` | Yes | ConfigMap | TRIRIGA context path (default: `/tririga`) |
| `MAS_REDIRECT_URL` | Yes | ConfigMap | Post-login redirect URL |
| `MAS_REST_API` | Yes | ConfigMap | SSOConnect REST path |
| `TRIRIGA_USERNAME` | Yes | ConfigMap | Service account email for SOAP calls |
| `TRIRIGA_PASSWORD` | Yes | Secret | Service account password |
| `BRIDGE_ISSUER_URL` | Yes | ConfigMap | Bridge's OIDC issuer URL |
| `MAS_OIDC_CLIENT_ID` | Yes | ConfigMap | OIDC client ID (default: `mas-facilities`) |
| `MAS_OIDC_CLIENT_SECRET` | Yes | Secret | Shared secret with Liberty |
| `MAS_OIDC_REDIRECT_URI` | Yes | ConfigMap | TRIRIGA's Liberty OIDC callback URL |
| `MAS_OAUTH_CLIENT_REDIRECT_URI` | No | ConfigMap | Liberty's OP OIDC callback URL (second redirect URI) |
| `GRAPH_API_ENABLED` | No | ConfigMap | Enable Graph API fallback (default: `true`) |
| `SPRING_PROFILES_ACTIVE` | Yes | ConfigMap | Must be `openshift` |

---

*End of document*
