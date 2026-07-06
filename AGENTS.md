# Project Context

SSO bridge (SAML) for eCIFM that validates Entra ID group membership during login flow and syncs groups to Tririga/MAS Core.

## Architecture

- Spring Boot app deployed on OpenShift (`ecifm-sso-bridge`)
- SAML bridge handles login → ACS handler runs group sync → redirects to Tririga
- Group membership resolved from Entra ID via Microsoft Graph API (Application permission, not delegated)
- Groups written to `cstNewADGroupTX` on `triPeople` record via Business Connect SOAP `saveRecord`

## Key Constraints

- **MAS Core managed records**: Workflow transitions (e.g. `cstValidateADGroup`) are **denied** by MAS Core on managed triPeople records → `saveRecord` with `actionName` fails with `PlatformRuntimeException`
- **Fix**: `saveRecord` without `actionName` succeeds; field `cstNewADGroupTX` is written directly. `pollForActiveStatus` also removed since no workflow action runs.
- The `peopleGroupFieldAction` (`cstValidateADGroup`) is still injected via `@Value` but **not used** (commented out in `MasGroupSyncService.java:189-192`)

## Configuration (Configmap: `ecifm-bridge-config`)

- `MAS_PEOPLE_GROUP_FIELD=cstNewADGroupTX` — write field
- `MAS_NAMED_QUERY_GROUP_COLUMN=cstCurrentADGroupTX` — read column from named query

## Named Query Details

- **Name**: `cstPeople - INTERFACE - Get SSO User for Access Recertification`
- **Columns**: `triUserNameTX`, `triNameTX`, `triRecordIdSY`, `cstCurrentADGroupTX`, `cstNewADGroupTX`, `triStatusCL`, `triEmailTX`
- **Filter field**: `triEmailTX`

## App Registration (Entra ID)

- **Client ID**: `cbcea157-2c35-4ce3-b86c-782282e00857`
- **Tenant**: `c99cc570-ba4f-474e-897d-22255a3cecd7`
- **Graph API permission**: `GroupMember.ReadAll` Application type — admin consent granted (tested: 403 resolved, Graph API works)
- **Redirect URIs**: `https://ecifm-sso-bridge-ecifm-sso-bridge.apps.npos2.ecifmdev.net/login/oauth2/code/entra-id`

## Endpoints

| Endpoint | Description |
|---|---|
| `/local/test-sync-groups?email=&groups=` | Full sync pipeline test (permitAll) — confirmed working with `success: true, wasSynced: true` |
| `/local/test-tririga-query?email=` | Named query + payload preview (requires auth) |
| `/login/oauth2/code/entra-id` | OAuth2 callback |
| `/acs` | SAML ACS endpoint |
| `/actuator/health/liveness`, `/actuator/health/readiness` | OpenShift health checks |

## Test User

`tarun.suneja@ecifm.com` — No current AD group (`cstCurrentADGroupTX = null`). Entra ID group: `cst_ECIFM_All_Users_Facilities`.

## Key Files

- `src/main/java/com/ecifm/saml/bridge/service/MasGroupSyncService.java`: Core sync logic
- `src/main/java/com/ecifm/saml/bridge/service/TririgaWsClient.java`: SOAP client (`saveRecord`, `runNamedQueryMultiBo`)
- `src/main/java/com/ecifm/saml/bridge/controller/AcsHandlerController.java`: SSO handler, test endpoints
- `src/main/java/com/ecifm/saml/bridge/config/SecurityConfig.java`: Security chain config

## Build & Deploy

- Builds committed to `main` branch, pushed to GitHub
- OpenShift build config `ecifm-sso-bridge` auto-triggers on push
- Image: `image-registry.openshift-image-registry.svc:5000/ecifm-sso-bridge/ecifm-sso-bridge`
- Route: `https://ecifm-sso-bridge-ecifm-sso-bridge.apps.npos2.ecifmdev.net`

## Remaining / Open Items

1. Full end-to-end test: initiate login via SAML bridge → authenticate with Entra ID → verify group sync on redirect
2. Investigate how MAS Core manages user activation based on `cstNewADGroupTX` value (may have own mechanism)

## Reference Project

`D:\00_TRIRIGA\2026\UMN\00_Work_Space\ecifmSSOHelper_Project` — workflow action pattern (`cstValidateADGroup` on save) is **not compatible** with MAS Core managed records.

## MAS-Side Configuration

See `docs/MAS_CONFIGURATION.md` for detailed MAS-side configuration requirements:
1. TRIRIGA object fields (`triPeople`)
2. Named query definition
3. SSOConnect IConnect plugin deployment
4. OpenShift ConfigMap/Secrets
5. MAS Core IDPCfg pointing to bridge
6. OIDC credentials secret (shared secret)
7. TLS certificate chain requirements
8. Data flow (no workflow action)
