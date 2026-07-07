# Project Context

SSO bridge (SAML) for eCIFM that validates Entra ID group membership during login flow and syncs groups to Tririga/MAS Core.

## Architecture

- Spring Boot app deployed on OpenShift (`ecifm-sso-bridge`)
- SAML bridge handles login → ACS handler runs group sync → redirects to Tririga
- Group membership resolved from Entra ID via Microsoft Graph API (Application permission, not delegated)
- Groups written to `cstNewADGroupTX` on `triPeople` record via Business Connect SOAP `saveRecord`

## Key Constraints

- **MAS Core managed records**: Workflow transitions (e.g. `cstValidateADGroup`) were previously denied by MAS Core on managed triPeople records → `saveRecord` with `actionName` failed with `PlatformRuntimeException`
- **Current state**: The `cstValidateADGroup` workflow action is now **re-enabled** in `MasGroupSyncService.java` (lines 189-192). Polling for active status via `pollForActiveStatus` is also re-enabled.
- If MAS Core still denies the transition, fallback: `saveRecord` without `actionName` writes field `cstNewADGroupTX` directly.

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

- `src/main/java/com/ecifm/saml/bridge/service/MasGroupSyncService.java`: Core sync logic (now replicates `cstPeople - Synchronous - Apply Template to Employee and Consultant` workflow)
- `src/main/java/com/ecifm/saml/bridge/service/TririgaWsClient.java`: SOAP client (`saveRecord`, `runNamedQueryMultiBo`, `associateRecords`, `deassociateRecords`, `getAssociatedRecords`, `getAssociationDefinitionsByName`)
- `src/main/java/com/ecifm/saml/bridge/controller/AcsHandlerController.java`: SSO handler, test endpoints (`/local/test-associations`, `/local/test-people-template-query`, `/local/test-sync-associations`)
- `src/main/java/com/ecifm/saml/bridge/config/SecurityConfig.java`: Security chain config

## Build & Deploy

- Builds committed to `main` branch, pushed to GitHub
- OpenShift build config `ecifm-sso-bridge` auto-triggers on push
- Image: `image-registry.openshift-image-registry.svc:5000/ecifm-sso-bridge/ecifm-sso-bridge`
- Route: `https://ecifm-sso-bridge-ecifm-sso-bridge.apps.npos2.ecifmdev.net`
- Local build: `$env:JAVA_HOME="C:\Program Files\Java\jdk-17"; mvn package -DskipTests` (JAVA_HOME must point to JDK 17+)

## Remaining / Open Items

1. Full end-to-end test: initiate login via SAML bridge → authenticate with Entra ID → verify group sync on redirect
2. Investigate how MAS Core manages user activation based on `cstNewADGroupTX` value (may have own mechanism)
3. Test `/local/test-people-template-query` to confirm the named query works and returns People Template records keyed by `cstADGroupTX`
4. Test `/local/test-associations` to discover the association name used for Group Details / Licence Details on triPeople
5. Test `/local/test-sync-associations` to run the full workflow replication pipeline

## Workflow Replication (Build 120+)

The `cstPeople - Synchronous - Apply Template to Employee and Consultant` workflow is now replicated in Java in `MasGroupSyncService.updatePeopleGroups()`:

1. **Field write** → `cstNewADGroupTX`, `cstPreviousADGroupTX`, `cstCurrentADGroupTX` via `saveRecord`
2. **Find People Template** → named query `cstPeople - Query - Get All the People Templates` filtered by `cstADGroupTX = newGroup`
3. **Deassociate old details** → `getAssociatedRecords(recordId, "Associated To")` → `saveRecord` section rows with `action="delete"` per child
4. **Associate template details** → copy Group Details / Licence Details from template to user via `saveRecord` section rows with `action="add"`
5. **Activate** → `triggerActions("triActivate")`
6. **Poll** → `pollForActiveStatus`

### New Config Properties (Configmap: `ecifm-bridge-config`)

| Env Var | Default | Purpose |
|---|---|---|
| `MAS_PEOPLE_CURRENT_GROUP_FIELD` | `cstCurrentADGroupTX` | Current group field name |
| `MAS_PEOPLE_PREV_GROUP_FIELD` | `cstPreviousADGroupTX` | Previous group field name |
| `MAS_TEMPLATE_QUERY_NAME` | `cstPeople - Query - Get All the People Templates` | Query to find templates |
| `MAS_TEMPLATE_FILTER_FIELD` | `cstADGroupTX` | Template filter field (maps AD group to template) |
| `MAS_TEMPLATE_GROUP_DETAILS_SECTION` | `triGroupsDetails` | Section for group details |
| `MAS_TEMPLATE_LICENCE_DETAILS_SECTION` | `triLicenceDetails` | Section for licence details |
| `MAS_TEMPLATE_HOME_PAGE_FIELD` | `triHomePageLI` | Home page field from template |
| `MAS_TEMPLATE_MENU_FIELD` | `triMenuLI` | Menu field from template |

### New Test Endpoints

| Endpoint | Description |
|---|---|
| `/local/test-associations?email=` | Lists all association definitions on triPeople and existing associated records |
| `/local/test-people-template-query?adGroup=` | Queries People Template records for a given AD group name |
| `/local/test-sync-associations?email=&groups=` | Runs the full workflow replication pipeline |

## Reference Project

`D:\00_TRIRIGA\2026\UMN\00_Work_Space\ecifmSSOHelper_Project` — workflow action pattern (`cstValidateADGroup` on save) is **not compatible** with MAS Core managed records.

## Workflow Definition (cstPeople - Synchronous - Apply Template to Employee and Consultant)

Full workflow discovered in `docs/WORKFLOW_DEFINITION.txt` — the `cstValidateADGroup` action triggers this workflow which:
1. Queries People Templates (triPeople records keyed by `cstADGroupTX`)
2. Matches template by `cstADGroupTX == cstNewADGroupTX` (fallback: "Request Central")
3. Removes existing Group Details / Licence Details child records
4. Copies template child records (Group Details, Licence Details) to user
5. Updates `cstPreviousADGroupTX`, `cstCurrentADGroupTX`, `triHomePageLI`, `triMenuLI`
6. Triggers `triActivate` state transition

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
