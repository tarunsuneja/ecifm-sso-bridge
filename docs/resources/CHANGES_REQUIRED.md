# OIDC Bridge — MAS OpenShift Resources That Need Changes

> **Date:** 2026-07-04  
> **Cluster:** NPOS2  
> **MAS Instance:** inst1

---

## Overview

Only **1 resource** in **1 namespace** needs to change to point MAS's authentication from Entra ID directly to the bridge. All other files below are saved for reference.

---

## 1. Namespace: `mas-inst1-core` — The Only Change

### 1.1 `IDPCfg` — `inst1-oidc-default-system` ← **CHANGE THIS**

**File reference:** `docs/resources/idpcfg-current.yaml`

| Field | Current Value | New Value |
|-------|---------------|-----------|
| `oidc.discoveryEndpointUrl` | `https://login.microsoftonline.com/c99cc570-ba4f-474e-897d-22255a3cecd7/v2.0/.well-known/openid-configuration` | `https://ecifm-sso-bridge-ecifm-sso-bridge.apps.npos2.ecifmdev.net/.well-known/openid-configuration` |
| `oidc.credentials.secretName` | `inst1-usersupplied-oidc-default-creds-system` | `inst1-usersupplied-oidc-bridge-creds-system` |
| `certificates` | Contains DigiCert + Intermediate CA | Remove (not needed — bridge uses cluster TLS) |

**New credentials secret to create:**

```bash
oc create secret generic inst1-usersupplied-oidc-bridge-creds-system \
  -n mas-inst1-core \
  --type=Opaque \
  --from-literal=clientId=mas-facilities \
  --from-literal=clientSecret=fMkWmZx8qZ8d9R/f+40lWrJRlTCzpwprxGqoWvXURU4=
```

### 1.2 `Secret` — `inst1-usersupplied-oidc-default-creds-system` ← **REPLACED BY NEW SECRET**

**File reference:** `docs/resources/secret-oidc-creds-current.yaml`

This secret stores the current Entra ID credentials (`clientId: cbcea157-2c35-4ce3-b86c-782282e00857`, `clientSecret: YUw8Q~W...`). Will be replaced by the new bridge credentials secret. **Do not delete** until the new one is confirmed working.

### 1.3 `ConfigMap` — `mas-multi-oidc` ← **AUTO-UPDATED BY OPERATOR**

**File reference:** `docs/resources/configmap-mas-multi-oidc.yaml`

This is generated automatically from the `IDPCfg` resource. When the IDPCfg is updated, the MAS operator reconciles this ConfigMap, which in turn updates Liberty's `oidc.xml`. No manual changes needed.

### 1.4 `CoreIDP` — `inst1-coreidp` ← **NO CHANGE NEEDED**

**File reference:** `docs/resources/coreidp-inst1-coreidp.yaml`

Contains base CoreIDP settings (CORS, SSO timeouts, custom login page). These remain as-is.

### 1.5 `Secret` — `inst1-credentials-oauth-admin` ← **NO CHANGE NEEDED**

**File reference:** `docs/resources/secret-core-oauth-admin.yaml`

OAuth admin credentials for Liberty itself. Not affected.

### 1.6 `Secret` — `inst1-credentials-oauth-client` ← **NO CHANGE NEEDED**

**File reference:** `docs/resources/secret-core-oauth-client.yaml`

Internal OAuth client (`bax4KOhQPuOFL8FOS6Gszr84I9eFdQTb`) used by MAS admin dashboard and homepage. Not affected.

---

## 2. Namespace: `mas-inst1-facilities` — No Changes Needed

### 2.1 `Secret` — `inst1-main-credentials-oauth-facilities-liberty` ← **NO CHANGE**

**File reference:** `docs/resources/secret-facilities-oidc-liberty.yaml`

Contains the Liberty OIDC client `oidc.xml` for facilities. Points to the Core IDP (`auth.inst1.../MaximoAppSuite`). This does NOT change — facilities still uses the same Core IDP; only the Core IDP's external provider changes.

### 2.2 `Secret` — `inst1-coreidp-system-binding` ← **NO CHANGE**

**File reference:** `docs/resources/secret-facilities-coreidp-binding.yaml`

Points to Core IDP's internal/external URLs. Stays the same.

### 2.3 `Secret` — `inst1-credentials-oauth-facilities` ← **NO CHANGE**

**File reference:** `docs/resources/secret-facilities-oauth-creds.yaml`

OAuth credentials for the `facilities` client. Stays the same.

---

## 3. Namespace: `mas-inst1-manage` — No Changes Needed

### 3.1 `Secret` — `inst1-coreidp-system-binding` ← **NO CHANGE**

**File reference:** `docs/resources/secret-manage-coreidp-binding.yaml`

Same as facilities — points to Core IDP. Stays the same.

### 3.2 `Secret` — `inst1-credentials-oauth-manage` ← **NO CHANGE**

**File reference:** `docs/resources/secret-manage-oauth-creds.yaml`

OAuth credentials for the `manage` client. Stays the same.

---

## Summary

| # | Action | Resource | Namespace | CLI Command |
|---|--------|----------|-----------|-------------|
| 1 | Create | New secret `inst1-usersupplied-oidc-bridge-creds-system` | `mas-inst1-core` | `oc create secret generic ...` |
| 2 | Update | `IDPCfg` `inst1-oidc-default-system` | `mas-inst1-core` | `oc apply -f idpcfg-bridge.yaml` |
| 3 | Verify | Bridge pod is running new code | `ecifm-sso-bridge` | `oc rollout status` |
| 4 | Test | All 3 apps authenticate through bridge | — | Visit TRIRIGA/Manage URLs |

**Everything else stays as-is.** The Core IDP handles the OIDC client registration for all apps (facilities, manage, etc.) — only the external provider pointer changes.

---

## Files in This Directory

| File | Source Namespace | Source Resource | Change Needed? |
|------|-----------------|----------------|----------------|
| `idpcfg-current.yaml` | `mas-inst1-core` | `IDPCfg inst1-oidc-default-system` | **YES** |
| `secret-oidc-creds-current.yaml` | `mas-inst1-core` | Secret `inst1-usersupplied-oidc-default-creds-system` | **Replace** |
| `configmap-mas-multi-oidc.yaml` | `mas-inst1-core` | ConfigMap `mas-multi-oidc` | Auto-updated |
| `coreidp-inst1-coreidp.yaml` | `mas-inst1-core` | CoreIDP `inst1-coreidp` | No |
| `secret-core-oauth-admin.yaml` | `mas-inst1-core` | Secret `inst1-credentials-oauth-admin` | No |
| `secret-core-oauth-client.yaml` | `mas-inst1-core` | Secret `inst1-credentials-oauth-client` | No |
| `secret-facilities-oidc-liberty.yaml` | `mas-inst1-facilities` | Secret `inst1-main-credentials-oauth-facilities-liberty` | No |
| `secret-facilities-coreidp-binding.yaml` | `mas-inst1-facilities` | Secret `inst1-coreidp-system-binding` | No |
| `secret-facilities-oauth-creds.yaml` | `mas-inst1-facilities` | Secret `inst1-credentials-oauth-facilities` | No |
| `secret-manage-coreidp-binding.yaml` | `mas-inst1-manage` | Secret `inst1-coreidp-system-binding` | No |
| `secret-manage-oauth-creds.yaml` | `mas-inst1-manage` | Secret `inst1-credentials-oauth-manage` | No |
