# Rollback Instructions

If the bridge OIDC IdP migration fails or needs to be reverted, follow these steps to restore the original Entra AD SSO configuration.

## Overview of Changes Made

| Resource | Namespace | Change | 
|---|---|---|
| `IDPCfg/inst1-oidc-default-system` | `mas-inst1-core` | Updated `discoveryEndpointUrl` → bridge, changed `credentials.secretName` → bridge secret, removed `certificates` array, renamed `displayName` |
| `Secret/inst1-usersupplied-oidc-bridge-creds-system` | `mas-inst1-core` | **NEW** — created with `mas-facilities` client credentials |
| `Deployment/ecifm-sso-bridge` | `ecifm-sso-bridge` | Updated to use v0.2.0 image (OIDC AS enabled) |
| `ConfigMap/ecifm-sso-bridge` | `ecifm-sso-bridge` | Added `BRIDGE_ISSUER_URL`, `MAS_OIDC_CLIENT_ID`, `MAS_OIDC_REDIRECT_URI` (unchanged from deploy, already had) |
| `Secret/ecifm-sso-bridge` | `ecifm-sso-bridge` | Added `MAS_OIDC_CLIENT_SECRET` (unchanged from deploy, already had) |
| Bridge source code | Git repo | Added Spring Auth Server, OIDC IdP endpoints, RS256 signing |

## Step 1: Restore IDPCfg to Original Entra AD SSO

```bash
oc apply -f - <<'ENDYAML'
apiVersion: config.mas.ibm.com/v1
kind: IDPCfg
metadata:
  labels:
    mas.ibm.com/configId: default
    mas.ibm.com/configScope: system
    mas.ibm.com/instanceId: inst1
  name: inst1-oidc-default-system
  namespace: mas-inst1-core
spec:
  certificates:
  - alias: DigiCertGlobalRootG2
    crt: |
      -----BEGIN CERTIFICATE-----
      MIIDjjCCAnagAwIBAgIQAzrx5qcRqaC7KGSxHQn65TANBgkqhkiG9w0BAQsFADBh
      MQswCQYDVQQGEwJVUzEVMBMGA1UEChMMRGlnaUNlcnQgSW5jMRkwFwYDVQQLExB3
      d3cuZGlnaWNlcnQuY29tMSAwHgYDVQQDExdEaWdpQ2VydCBHbG9iYWwgUm9vdCBH
      MjAeFw0xMzA4MDExMjAwMDBaFw0zODAxMTUxMjAwMDBaMGExCzAJBgNVBAYTAlVT
      MRUwEwYDVQQKEwxEaWdpQ2VydCBJbmMxGTAXBgNVBAsTEHd3dy5kaWdpY2VydC5j
      b20xIDAeBgNVBAMTF0RpZ2lDZXJ0IEdsb2JhbCBSb290IEcyMIIBIjANBgkqhkiG
      9w0BAQEFAAOCAQ8AMIIBCgKCAQEAuzfNNNx7a8myaJCtSnX/RrohCgiN9RlUyfuI
      2/Ou8jqJkTx65qsGGmvPrC3oXgkkRLpimn7Wo6h+4FR1IAWsULecYxpsMNzaHxmx
      1x7e/dfgy5SDN67sH0NO3Xss0r0upS/kqbitOtSZpLYl6ZtrAGCSYP9PIUkY92eQ
      q2EGnI/yuum06ZIya7XzV+hdG82MHauVBJVJ8zUtluNJbd134/tJS7SsVQepj5Wz
      tCO7TG1F8PapspUwtP1MVYwnSlcUfIKdzXOS0xZKBgyMUNGPHgm+F6HmIcr9g+UQ
      vIOlCsRnKPZzFBQ9RnbDhxSJITRNrw9FDKZJobq7nMWxM4MphQIDAQABo0IwQDAP
      BgNVHRMBAf8EBTADAQH/MA4GA1UdDwEB/wQEAwIBhjAdBgNVHQ4EFgQUTiJUIBiV
      5uNu5g/6+rkS7QYXjzkwDQYJKoZIhvcNAQELBQADggEBAGBnKJRvDkhj6zHd6mcY
      1Yl9PMWLSn/pvtsrF9+wX3N3KjITOYFnQoQj8kVnNeyIv/iPsGEMNKSuIEyExtv4
      NeF22d+mQrvHRAiGfz0JFrabA0UWTW98kndth/Jsw1HKj2ZL7tcu7XUIOGZX1NG
      Fdtom/DzMNU+MeKNhJ7jitralj41E6Vf8PlwUHBHQRFXGU7Aj64GxJUTFy8bJZ91
      8rGOmaFvE7FBcf6IKshPECBV1/MUReXgRPTqh5Uykw7+U0b6LJ3/iyK5S9kJRaTe
      pLiaWN0bfVKfjllDiIGknibVb63dDcY3fe0Dkhvld1927jyNxF1WW6LZZm6zNTfl
      MrY=
      -----END CERTIFICATE-----
  - alias: IntermediateCA
    crt: |
      -----BEGIN CERTIFICATE-----
      MIIFrDCCBJSgAwIBAgIQCkOpUJsBNS+JlXnscgi6UDANBgkqhkiG9w0BAQwFADBh
      MQswCQYDVQQGEwJVUzEVMBMGA1UEChMMRGlnaUNlcnQgSW5jMRkwFwYDVQQLExB3
      d3cuZGlnaWNlcnQuY29tMSAwHgYDVQQDExdEaWdpQ2VydCBHbG9iYWwgUm9vdCBH
      MjAeFw0yMzA2MDgwMDAwMDBaFw0yNjA4MjUyMzU5NTlaMF0xCzAJBgNVBAYTAlVT
      MR4wHAYDVQQKExVNaWNyb3NvZnQgQ29ycG9yYXRpb24xLjAsBgNVBAMTJU1pY3Jv
      c29mdCBBenVyZSBSU0EgVExTIElzc3VpbmcgQ0EgMDcwggIiMA0GCSqGSIb3DQEB
      AQUAA4ICDwAwggIKAoICAQC1ZF7KYus5OO3GWqJoR4xznLDNCjocogqeCIVdi4eE
      BmF3zIYeuXXNoJAUF+mn86NBt3yMM0559JZDkiSDi9MpA2By4yqQlTHzfbOrvs7I
      4LWsOYTEClVFQgzXqa2ps2g855HPQW1hZXVh/yfmbtrCNVa//G7FPDqSdrAQ+M8w
      0364kyZApds/RPcqGORjZNokrNzYcGub27vqE6BGP6XeQO5YDFobi9BvvTOO+ZA9
      HGIU7FbdLhRm6YP+FO8NRpvterfqZrRt3bTn8GT5LsOTzIQgJMt4/RWLF4EKNc97
      CXOSCZFn7mFNx4SzTvy23B46z9dQPfWBfTFaxU5pIa0uVWv+jFjG7l1odu0WZqBd
      j0xnvXggu564CXmLz8F3draOH6XS7Ys9sTVM3Ow20MJyHtuA3hBDv+tgRhrGvNRD
      MbSzTO6axNWvL46HWVEChHYlxVBCTfSQmpbcAdZOQtUfs9E4sCFrqKcRPdg7ryhY
      fGbj3q0SLh55559ITttdyYE+wE4RhODgILQ3MaYZoyiL1E/4jqCOoRaFhF5R++vb
      YpemcpWx7unptfOpPRRnnN4U3pqZDj4yXexcyS52Rd8BthFY/cBg8XIR42BPeVRl
      OckZ+ttduvKVbvmGf+rFCSUoy1tyRwQNXzqeZTLrX+REqgFDOMVe0I49Frc2/Avw
      3wIDAQABo4IBYjCCAV4wEgYDVR0TAQH/BAgwBgEB/wIBADAdBgNVHQ4EFgQUzhUW
      O+oCo6Zr2tkr/eWMUr56UKgwHwYDVR0jBBgwFoAUTiJUIBiV5uNu5g/6+rkS7QYX
      jzkwDgYDVR0PAQH/BAQDAgGGMB0GA1UdJQQWMBQGCCsGAQUFBwMBBggrBgEFBQcD
      AjB2BggrBgEFBQcBAQRqMGgwJAYIKwYBBQUHMAGGGGh0dHA6Ly9vY3NwLmRpZ2lj
      ZXJ0LmNvbTBABggrBgEFBQcwAoY0aHR0cDovL2NhY2VydHMuZGlnaWNlcnQuY29t
      L0RpZ2lDZXJ0R2xvYmFsUm9vdEcyLmNydDBCBgNVHR8EOzA5MDegNaAzhjFodHRw
      Oi8vY3JsMy5kaWdpY2VydC5jb20vRGlnaUNlcnRHbG9iYWxSb290RzIuY3JsMB0G
      A1UdIAQWMBQwCAYGZ4EMAQIBMAgGBmeBDAECAjANBgkqhkiG9w0BAQwFAAOCAQEA
      bbV8m4/LCSvb0nBF9jb7MVLH/9JjHGbn0QjB4R4bMlGHbDXDWtW9pFqMPrRh2Q76
      Bqm+yrrgX83jPZAcvOd7F7+lzDxZnYoFEWhxW9WnuM8Te5x6HBPCPRbIuzf9pSUT
      /ozvbKFCDxxgC2xKmgp6NwxRuGcy5KQQh4xkq/hJrnnF3RLakrkUBYFPUneip+wS
      BzAfK3jHXnkNCPNvKeLIXfLMsffEzP/j8hFkjWL3oh5yaj1HmlW8RE4Tl/GdUVzQ
      D1x42VSusQuRGtuSxLhzBNBeJtyD//2u7wY2uLYpgK0o3X0iIJmwpt7Ovp6Bs4tI
      E/peia+Qcdk9Qsr+1VgCGA==
      -----END CERTIFICATE-----
  displayName: Entra AD SSO
  oidc:
    credentials:
      secretName: inst1-usersupplied-oidc-default-creds-system
    discoveryEndpointUrl: https://login.microsoftonline.com/c99cc570-ba4f-474e-897d-22255a3cecd7/v2.0/.well-known/openid-configuration
    signatureAlgorithm: RS256
    tokenEndpointAuthMethod: post
    tokenEndpointAuthSigningAlgorithm: RS256
    userIdentifier: preferred_username
ENDYAML
```

## Step 2: Delete the Bridge Credentials Secret

```bash
oc delete secret inst1-usersupplied-oidc-bridge-creds-system -n mas-inst1-core
```

(The original `inst1-usersupplied-oidc-default-creds-system` secret was never deleted — it still exists with the original Entra ID client values.)

## Step 3: Verify IDPCfg Reconciliation

```bash
oc get IDPCfg inst1-oidc-default-system -n mas-inst1-core -o yaml | grep -E "generation:|message:|type:"
```

Wait for `type: Ready` with `message: OIDC IDP validated` and `versions.generation` matching the updated spec.

## Step 4: (Optional) Roll Back Bridge Code

If you also want to revert the bridge application itself to the pre-OIDC version:

```bash
# Revert to tag before OIDC IdP changes
git checkout v0.1.0-bridge-client-only

# Rebuild and push via OpenShift
oc start-build ecifm-sso-bridge --from-dir=. --follow --wait -n ecifm-sso-bridge

# Restart deployment
oc rollout restart deployment/ecifm-sso-bridge -n ecifm-sso-bridge
```

Or to keep the new code but just roll back MAS config, skip this step.

## Step 5: (Optional) Roll Back OpenShift ConfigMap/Secret

The bridge ConfigMap and Secret changes (`BRIDGE_ISSUER_URL`, `MAS_OIDC_CLIENT_ID`, `MAS_OIDC_REDIRECT_URI`, `MAS_OIDC_CLIENT_SECRET`) are unused by the pre-OIDC bridge code — they're additive and won't break anything if left in place. No action needed.

## Snapshot Files Saved

During the migration, the following current-state YAML files were saved for reference:

- `docs/resources/post-migration/idpcfg-bridge.yaml` — Current IDPCfg pointing to bridge
- `docs/resources/post-migration/secret-bridge-creds.yaml` — Bridge OIDC client credentials secret
- `docs/resources/post-migration/secret-default-creds-PREVIOUS.yaml` — Original Entra ID OIDC client credentials (unchanged, still present)
- `docs/resources/deployment-bridge-current.yaml` — Current bridge deployment
- `docs/resources/configmap-bridge-current.yaml` — Current bridge ConfigMap

## Test After Rollback

After rolling back, verify the old flow works:

1. Open `https://main.facilities.inst1.apps.npos2.ecifmdev.net` in incognito
2. It should redirect to `https://login.microsoftonline.com/...` (Entra ID) instead of the bridge
3. Log in — should return to TRIRIGA normally
