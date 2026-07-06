# MAS-Side Configuration Required for Group Sync

## 1. TRIRIGA Object Structure (`triPeople`)

The `triPeople` BO must have these fields defined:

| Field | Purpose | Set by |
|-------|---------|--------|
| `triUserNameTX` | User login name | TRIRIGA |
| `triEmailTX` | Email (filter field in named query) | TRIRIGA |
| `triRecordIdSY` | System record ID | TRIRIGA (auto) |
| **`cstCurrentADGroupTX`** | Current/active AD group (read column) | MAS workflow or manual |
| **`cstNewADGroupTX`** | New AD group to apply (**bridge writes here**) | **Bridge via `saveRecord`** |
| `triStatusCL` | User status (e.g. "Active User") | TRIRIGA |
| `triNameTX` | Display name | TRIRIGA |

## 2. Named Query

Must exist in TRIRIGA, defined on `triPeople`:

- **Name**: `cstPeople - INTERFACE - Get SSO User for Access Recertification`
- **Filter field**: `triEmailTX`
- **Returned columns**: `triUserNameTX`, `triNameTX`, `triRecordIdSY`, `cstCurrentADGroupTX`, `cstNewADGroupTX`, `triStatusCL`, `triEmailTX`

The bridge calls this query to look up the user's record and read their current group (`cstCurrentADGroupTX`).

## 3. SSOConnect IConnect Plugin (DEPLOYED IN TRIRIGA)

The bridge calls a REST endpoint at:
```
{mas-base-url}/tririga/html/en/default/rest/SSOConnect?userName={0}&adGroupName={1}
```

This endpoint is implemented by the reference project (`SSOConnect.java` from `ecifmSSOHelper_Project`) and deployed as an IConnect plugin **inside TRIRIGA**. Without this plugin deployed, the group sync call will return `SSOConnect Failure: 404 Not Found`.

## 4. OpenShift: ConfigMap (`ecifm-bridge-config`) — `ecifm-sso-bridge` namespace

| Key | Value | Notes |
|-----|-------|-------|
| `MAS_BASE_URL` | `https://main.facilities.inst1.apps.npos2.ecifmdev.net` | TRIRIGA/MAS base URL |
| `MAS_CONTEXT` | `/tririga` | TRIRIGA context path |
| `MAS_REST_API` | `/html/en/default/rest/SSOConnect` | REST endpoint for SSOConnect plugin |
| **`MAS_PEOPLE_GROUP_FIELD`** | `cstNewADGroupTX` | Field the bridge writes group to |
| **`MAS_NAMED_QUERY_GROUP_COLUMN`** | `cstCurrentADGroupTX` | Column read from named query |
| `MAS_NAMED_QUERY_NAME` | `cstPeople - INTERFACE - Get SSO User for Access Recertification` | |
| `MAS_NAMED_QUERY_FILTER_FIELD` | `triEmailTX` | Filter by email |
| `MAS_NAMED_QUERY_MODULE` | `triPeople` | |
| `MAS_NAMED_QUERY_OBJECT_TYPE` | `triPeople` | |
| `AZURE_TENANT_ID` | `c99cc570-ba4f-474e-897d-22255a3cecd7` | |
| `AZURE_CLIENT_ID` | `cbcea157-2c35-4ce3-b86c-782282e00857` | |
| `AZURE_GROUPS` | `cst_ECIFM_All_Users_Facilities` | Allowed groups (comma-separated) |
| `BRIDGE_ISSUER_URL` | `https://ecifm-sso-bridge...` | Bridge's own issuer |
| `MAS_OIDC_CLIENT_ID` | `mas-facilities` | Must match Core IDP client |

## 5. OpenShift: Secrets (`ecifm-bridge-secrets`) — `ecifm-sso-bridge` namespace

| Key | Source |
|-----|--------|
| `AZURE_CLIENT_SECRET` | Entra ID app registration |
| `TRIRIGA_USERNAME` | Business Connect service account |
| `TRIRIGA_PASSWORD` | Business Connect service account password |
| `MAS_OIDC_CLIENT_SECRET` | Shared secret — **must match** Core IDP secret |

## 6. MAS Core IDP Configuration (IDPCfg) — `mas-inst1-core` namespace

Create/update `IDPCfg` to point MAS Core's OIDC provider to the bridge:

```yaml
spec:
  displayName: ECIFM SSO Bridge
  oidc:
    discoveryEndpointUrl: https://ecifm-sso-bridge-.../.well-known/openid-configuration
    userIdentifier: preferred_username
    signatureAlgorithm: RS256
    tokenEndpointAuthMethod: post
    tokenEndpointAuthSigningAlgorithm: RS256
    credentials:
      secretName: inst1-usersupplied-oidc-bridge-creds-system
```

**Remove** the `certificates` section (bridge uses cluster TLS).

**Important**: Set `authFilter: mas-oidc=oidc` to prevent infinite redirect loops (Liberty only redirects to bridge when the cookie is present).

## 7. OIDC Client Credentials Secret — `mas-inst1-core` namespace

```bash
oc create secret generic inst1-usersupplied-oidc-bridge-creds-system \
  -n mas-inst1-core \
  --type=Opaque \
  --from-literal=clientId=mas-facilities \
  --from-literal=clientSecret=<shared-secret>
```

The `clientSecret` must **exactly match** `MAS_OIDC_CLIENT_SECRET` in the bridge's secret. Liberty uses this to authenticate its token exchange with the bridge.

## 8. TLS Certificate Chain

The bridge route must present a complete, CA-signed certificate chain (leaf + intermediate + root). Liberty/JKS validation rejects self-signed certs. The CA cert should be:

- Added to the IDPCfg `certificates` section, OR
- Trusted at the cluster level via cluster-wide certificate injection

## 9. What Does NOT Need to Change

| Component | Reason |
|-----------|--------|
| Liberty OIDC client (`inst1-main-credentials-oauth-facilities-liberty`) | Still points to MAS Core IDP (`auth.inst1...`); only Core IDP's upstream changes |
| `cstValidateADGroup` workflow action | Not used by bridge (MAS Core denies transitions on managed records) |
| `triPeople` record management | MAS Core continues managing records; bridge only writes one field |

## 10. How the Data Flow Works (No Workflow Action)

```
User logs in → Bridge receives ACS callback
  → Bridge looks up user in TRIRIGA via named query (read cstCurrentADGroupTX)
  → Bridge resolves group membership from Entra ID Graph API
  → Bridge calls saveRecord on triPeople (writes to cstNewADGroupTX, NO actionName)
  → BRIDGE'S JOB ENDS HERE
  → [MAS Core: picks up cstNewADGroupTX value via its own mechanism — TBD]
```

**Open question**: How does MAS Core process the `cstNewADGroupTX` value to manage user activation/group assignment? This is environment-specific and may involve automation scripts, data sync rules, or manual processes.
