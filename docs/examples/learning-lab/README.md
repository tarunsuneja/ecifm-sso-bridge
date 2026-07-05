# Learning Lab — ecifm-saml-bridge

Hands-on code examples to understand the bridge's technology stack in 1 week.

## Quick Start

```bash
# Build and run
mvn clean package
mvn spring-boot:run

# Open the app
open http://localhost:8080
```

## 1-Week Schedule

| Day | Stage | What You'll Do | Run This |
|-----|-------|----------------|----------|
| Mon | 1 | Spring Boot basics | `curl localhost:8080/stage1/info` |
| Tue | 2 | Spring Security chains | `curl localhost:8080/api/public/ping` |
| Wed | 3 | OAuth2 Authorization Server | Open `http://localhost:8080` in browser |
| Thu | 4 | OpenShift YAMLs (read) | Review `openshift/*.yaml` |
| Fri | 5 | MAS Architecture (read) | Review `src/.../stage5/MasCheatsheet.java` |
| Fri | 6 | SOAP + TRIRIGA auth | Review `src/.../stage6/Ex2_AuthMethodComparison.java` |
| Sat | 7 | Entra ID integration | Read config + Graph API flow |
| Sun | 8 | Debugging methodology | Review curl/oc commands in `stage8/` |

## Stage-by-Stage Guide

### Stage 1: Spring Boot Foundation
- `Ex1_PropertyInjection.java` — `@Value` reads from `application.yml`
- `Ex2_DependencyInjection.java` — `@Service` + `@RestController` wiring
- `Ex3_ConfigurationBean.java` — `@Configuration` + `@Bean` programmatic beans
- `Ex4_ProfileDemo.java` — Profile switching (`dev` vs `prod`)

### Stage 2: Spring Security
- `Ex1_SingleFilterChain.java` — One chain: public vs protected routes
- `Ex2_MultiChain.java` — Three chains with `@Order`: `/api/**`, `/actuator/**`, everything else
- `Ex3_InMemoryUsers.java` — Role-based access (`USER` vs `ADMIN`)

### Stage 3: OAuth2 & OIDC
- `Ex1_AuthorizationServer.java` — Full OAuth2 AS: registered client, RSA keys, token customization

### Stage 4: OpenShift & Kubernetes
- `openshift/configmap.yaml` — Non-sensitive env vars
- `openshift/secret.yaml` — Base64-encoded secrets
- `openshift/deployment.yaml` — Pod replicas, probes, resources
- `openshift/service.yaml` — Stable ClusterIP endpoint
- `openshift/route.yaml` — Edge TLS termination

### Stage 5: MAS Architecture
- `MasCheatsheet.java` — Commands to map MAS, trace OIDC chain, decode Liberty config

### Stage 6: CXF SOAP & TRIRIGA Auth
- `Ex1_SoapClientExample.java` — SOAP client pattern (codegen, auth, session)
- `Ex2_AuthMethodComparison.java` — Why SOAP Basic works but REST Bearer fails

### Stage 7: Entra ID & Graph API
- `Ex1_EntraIdOidcClient.java` — Spring Security OAuth2 login with Entra ID
- `Ex2_GraphApiFallback.java` — Resolve >200 groups via Graph API

### Stage 8: Debugging Methodology
- `DebuggingMethodology.java` — curl commands, JWT decoder, redirect tracer, error decoder

## Mapping to Real Bridge Code

| Learning Example | Real Bridge File | What They Share |
|-----------------|------------------|-----------------|
| Stage 1: `Ex3_ConfigurationBean` | `AuthServerConfig.java:42-139` | `@Configuration` + `@Bean` pattern |
| Stage 2: `Ex2_MultiChain` | `SecurityConfig.java:20-61` | Multiple `@Order` filter chains |
| Stage 3: `Ex1_AuthorizationServer` | `AuthServerConfig.java:42-139` | OAuth2 AS, `RegisteredClient`, `JWKSource` |
| Stage 6: `Ex1_SoapClientExample` | `TririgaWsClient.java:138-221` | CXF SOAP, HTTP Basic, JSESSIONID |
| Stage 7: `Ex2_GraphApiFallback` | `EntraIdGroupResolver.java:54-58` | Graph API `/me/memberOf` |
| Stage 8: curl commands | Actual investigation steps | `oc get secret ... oidc.xml` decoding |
