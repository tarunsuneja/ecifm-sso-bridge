# Alternative Architectures for Group Sync

## Current Architecture Summary

Spring Boot bridge on OpenShift that:
1. Acts as OIDC Authorization Server for MAS Core IDP (auth proxy to Entra ID)
2. Resolves groups from Entra ID via Graph API
3. Queries TRIRIGA via SOAP named query (`cstCurrentADGroupTX`)
4. Writes to `cstNewADGroupTX` via Business Connect SOAP `saveRecord` (no workflow action)
5. Calls SSOConnect REST endpoint inside TRIRIGA

**Key constraint**: MAS Core denies workflow transitions (`cstValidateADGroup`) on managed `triPeople` records → `saveRecord` with `actionName` fails.

---

## Approach 1: Keep Current Architecture But Improve It (Recommended)

### Improvements

#### 1a. Move Group Sync Into OIDC Auth Flow

Today group sync is on `/redirect` (standalone endpoint). In the OIDC flow (MAS Core → Bridge → Entra ID), group sync never fires.

**Fix**: Hook into `AuthServerConfig.java` token customization to call `MasGroupSyncService.syncIfGroupsDiffer()` when the bridge issues tokens. Sync fires automatically on every login without users hitting a separate endpoint.

**Implementation sketch**:
```java
// In OAuth2TokenCustomizer<OAuth2TokenClaimsContext>
// After setting claims, trigger group sync
masGroupSyncService.syncIfGroupsDiffer(
    accessToken, 
    preferredUsername, 
    groupsFromToken
);
```

#### 1b. Replace SOAP saveRecord With OSLC REST

SOAP Business Connect is legacy and adds dependency on `TririgaWS.wsdl`. The bridge already has JSESSIONID from SOAP auth. TRIRIGA's OSLC REST APIs (`/oslc/*`) can write records directly.

**Pros**: Modern REST, no WSDL dependency, same auth (JSESSIONID cookie), no Business Connect overhead
**Cons**: OSLC has its own query syntax (RDF/XML or JSON), different field naming conventions

#### 1c. Replace SOAP Named Query With OSLC Query

Replace `runNamedQueryMultiBo` SOAP call with an OSLC REST query:
```
GET /oslc/triPeople?oslc.where=triEmailTX="user@domain.com"
```

**Pros**: Simpler, standard REST, no WSDL dependency
**Cons**: May not support all named query features (may need to map to OSLC query syntax)

#### 1d. Persistent JWKS for Multi-Replica

Bridge generates new RSA key pair on every startup. With 2+ replicas, token signed by pod A fails on pod B.

**Fix**: Store JWK set in a `Secret` or `ConfigMap` mounted at startup so all pods share the same keys.

**Implementation**: Generate keys once, store as JSON in a Secret, load at startup via `@Value` or mounted volume.

#### 1e. Async Graph API in Auth Flow

Graph API calls add latency to the auth redirect. User waits for group resolution before the redirect finishes.

**Fix**: 
- Return auth redirect immediately (fast path)
- Enqueue group sync as `@Async` / `@EventListener` (slow path)
- Group sync completes in background, field written eventually

---

## Approach 2: TRIRIGA IConnect Plugin (Logic Inside TRIRIGA)

Deploy a Java IConnect plugin inside TRIRIGA (extending reference `SSOConnect.java`) that:
- Calls Graph API directly to resolve group membership
- Updates `triPeople` record internally

### Flow

```
User logs in → TRIRIGA Liberty → IConnect plugin fires
  → Plugin calls Graph API (me/memberOf) 
  → Plugin updates cstNewADGroupTX on triPeople
  → No external bridge needed for sync
```

### Pros

| Aspect | Assessment |
|--------|-----------|
| No external bridge | Removes SOAP/REST network hop |
| Runs in TRIRIGA JVM | Direct API access, no auth proxy needed |
| Real-time | Trigger on user login/save event |

### Cons

| Aspect | Assessment |
|--------|-----------|
| MAS Core workflow block | Same constraint — MAS Core denies transitions on managed records. Plugin must write field directly without `actionName`. |
| Graph API from TRIRIGA | TRIRIGA Liberty needs HTTP client + truststore for Graph API calls. May need additional Liberty config. |
| Plugin deployment | IConnect plugin deployment is manual, requires TRIRIGA restart |
| 200-group limit | JWT `groups` claim limited to 200 groups. With delegated permissions, Graph API fallback needed. Entra ID Java SDK needs to run inside TRIRIGA JVM. |
| Managed records | MAS Core may reject direct record writes from plugins on managed records, not just workflow transitions. Untested. |

### Verdict

Only viable if MAS Core allows field writes from within TRIRIGA plugins on managed records (not just blocking workflow transitions). If allowed, eliminates the need for an external bridge for group sync. If blocked, same constraint applies as current approach.

---

## Approach 3: Entra ID SCIM Provisioning

Configure Entra ID to provision users and groups into MAS Core via SCIM (System for Cross-domain Identity Management).

### Flow

```
Entra ID (Azure AD) ──SCIM──→ SCIM Gateway ──SOAP/REST──→ TRIRIGA
```

### Pros

| Aspect | Assessment |
|--------|-----------|
| No custom code | Built into Entra ID Enterprise App provisioning |
| Entra ID handles retries | Retry logic, delta sync, error reporting built in |
| Real-time configurable | Provision on demand or on schedule |

### Cons

| Aspect | Assessment |
|--------|-----------|
| TRIRIGA/SCIM support | MAS Core / TRIRIGA does not natively support SCIM. Would need a SCIM-to-SOAP gateway — essentially building a custom bridge anyway. |
| Group-to-role mapping | SCIM provisions users/groups, but mapping AD groups to TRIRIGA roles still needs custom logic |
| Object mapping | SCIM schema (User, Group) must map to `triPeople` fields — non-trivial |

### Verdict

Entra ID SCIM requires a SCIM-compliant target. MAS Core / TRIRIGA is not SCIM-compliant. Would need a gateway (which is what the current bridge effectively is). Not practical without MAS adding SCIM support.

---

## Approach 4: Simplify Bridge — Pure OIDC Proxy, No Group Sync

Strip group sync from the bridge entirely. Bridge becomes a pure OIDC Authorization Server that proxies auth to Entra ID. Group management handled separately:

### Option A: TRIRIGA Reads JWT Groups Claim

TRIRIGA automation script reads the `groups` claim from the JWT on user login and updates the record internally.

### Option B: Scheduled CronJob (Batch Sync)

An OpenShift `CronJob` runs periodically (every 5 min, every hour) that:
1. Queries TRIRIGA for all users
2. Queries Entra ID for group membership (via Graph API with Application permission)
3. Batch-updates `cstNewADGroupTX` for any changes

### Option C: SAS Token / Custom Claim

Bridge embeds group membership as a custom claim in the JWT (already partially done). TRIRIGA Liberty reads the claim on token validation and processes it.

### Pros

| Aspect | Assessment |
|--------|-----------|
| Bridge is simple/focused | Auth only — less code, fewer failure modes |
| Group sync decoupled | Can be owned, operated, and scheduled independently |
| Batch sync is robust | Retries, error handling, idempotent |

### Cons

| Aspect | Assessment |
|--------|-----------|
| JWT group claim limited | Max 200 groups in JWT. Entra ID sends overage indicator beyond that. |
| Push complexity elsewhere | TRIRIGA automation or CronJob is another component to build/deploy |
| Real-time sync lost | Batch sync means lag between group change and TRIRIGA update (acceptable for most use cases) |
| TRIRIGA automation constraints | TRIRIGA automation scripts have limited HTTP client capabilities; may not be able to call Graph API |

### Verdict (CronJob approach)

Most practical of the "simplify bridge" options. A Python/Node.js script running as an OpenShift CronJob with:
- Application permission to Graph API (already working)
- SOAP/OSLC client to query and update TRIRIGA records
- Runs every N minutes

Proven pattern (idempotent batch sync), but introduces lag and another component.

---

## Comparison Matrix

| Criteria | Current (improved) | IConnect Plugin | SCIM | CronJob |
|----------|-------------------|----------------|------|---------|
| **Real-time sync** | Yes | Yes | Yes | No (batch lag) |
| **Custom code** | Medium | Medium | Low (gateway needed) | Medium |
| **MAS Core constraint** | Bypassed (no actionName) | Unknown (needs testing) | N/A | Bypassed (same fix) |
| **External deps** | SOAP/WSDL | Graph API SDK in TRIRIGA | SCIM gateway | None |
| **Deployment complexity** | Low (existing) | Medium (plugin + restart) | High (SCIM infra) | Low (CronJob) |
| **Operational burden** | Low | Medium | Low (Entra ID managed) | Low |
| **Multi-replica** | Fixed with persistent JWKS | N/A (single TRIRIGA JVM) | N/A | Yes (naturally) |
| **Maintainability** | Medium | Low (TRIRIGA version upgrades) | Medium | High (simple script) |

---

## Recommendation

**Keep current architecture and make these targeted improvements** (Approach 1):

### P0 — Ship-blocking
1. **Move group sync into OIDC auth flow** — Hook into `OAuth2TokenCustomizer` to trigger sync on every login. Today's `/redirect` endpoint is unused in the production auth chain.
2. **Persistent JWKS** — Store keys in a Secret so multi-replica works.

### P1 — Should do
3. **Replace SOAP with OSLC** — Remove `TririgaWS.wsdl` dependency. Use OSLC REST for both querying and updating records. The bridge already has JSESSIONID from initial SOAP auth — reuses the same session.

### P2 — Nice to have
4. **Async group resolution** — Wrap Graph API calls in `@Async` so auth redirect is not blocked by group resolution latency.
5. **Retry queue for failed syncs** — If `saveRecord` fails, retry with exponential backoff via a background queue.

---

## Key Files Reference

| File | Purpose |
|------|---------|
| `src/main/java/.../config/AuthServerConfig.java` | OIDC Authorization Server config, token customization |
| `src/main/java/.../service/MasGroupSyncService.java` | Core sync logic (where actionName was removed) |
| `src/main/java/.../service/TririgaWsClient.java` | SOAP client (target for OSLC migration) |
| `src/main/java/.../service/MasApiClient.java` | REST client (SSOConnect calls) |
| `src/main/java/.../controller/AcsHandlerController.java` | /redirect endpoint, test endpoints |
| `docs/MAS_CONFIGURATION.md` | MAS-side configuration requirements |
