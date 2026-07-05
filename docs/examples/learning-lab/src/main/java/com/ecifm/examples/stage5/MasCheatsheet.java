package com.ecifm.examples.stage5;

/*
 * ─────────────────────────────────────────────────────────────────
 * STAGE 5: MAS Architecture
 * ─────────────────────────────────────────────────────────────────
 *
 * This file is NOT runnable — it's a cheatsheet of commands
 * to explore a real MAS environment on OpenShift.
 *
 * PREREQUISITES:
 *   1. oc CLI logged into the cluster
 *   2. Permissions to view mas-inst1-* namespaces
 *
 * ─────────────────────────────────────────────────────────────────
 * Exercise 1: Map the MAS environment
 * ─────────────────────────────────────────────────────────────────
 *
 * # List all namespaces related to MAS instance 1
 * oc get projects | findstr "mas-inst1"
 *
 * # Expected output:
 *   mas-inst1-core          Active   <date>
 *   mas-inst1-facilities    Active   <date>
 *   mas-inst1-manage        Active   <date>
 *
 * ─────────────────────────────────────────────────────────────────
 * Exercise 2: Find the Core IDP
 * ─────────────────────────────────────────────────────────────────
 *
 * # Core IDP is the central OIDC provider for all MAS apps
 * oc describe deployment inst1-coreidp -n mas-inst1-core
 *
 * # Key env vars to look for:
 *   OIDC_ISSUER_URL=https://auth.inst1.apps.npos2.ecifmdev.net/oidc/endpoint/MaximoAppSuite
 *   MAS_INTERNAL_API_HOST=internalapi.mas-inst1-core.svc
 *
 * # The Core IDP login UI is a separate React SPA:
 * oc describe deployment inst1-coreidp-login -n mas-inst1-core
 *
 * ─────────────────────────────────────────────────────────────────
 * Exercise 3: Find the OIDC chain config
 * ─────────────────────────────────────────────────────────────────
 *
 * # The mas-multi-oidc ConfigMap defines upstream OIDC providers
 * oc get configmap mas-multi-oidc -n mas-inst1-core -o yaml
 *
 * # This is where the bridge is registered as an upstream provider
 * # Look for: configId: "default-oidc"
 *
 * # SAML providers are configured similarly:
 * oc get configmap mas-multi-saml-sp -n mas-inst1-core -o yaml
 *
 * ─────────────────────────────────────────────────────────────────
 * Exercise 4: Facilities Liberty server
 * ─────────────────────────────────────────────────────────────────
 *
 * # Facilities runs on WebSphere Liberty as a StatefulSet
 * oc describe statefulset inst1-main-appserver -n mas-inst1-facilities
 *
 * # StatefulSet (not Deployment) because Liberty needs stable
 * # hostnames for session replication and JMS.
 *
 * # Find Liberty's OIDC client config:
 * $secret = oc get secret -n mas-inst1-facilities | findstr "credentials-oauth"
 * $b64 = oc get secret $secret[0] -n mas-inst1-facilities -o jsonpath="{.data['oidc\.xml']}"
 * [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String($b64))
 *
 * # The oidc.xml should show:
 * #   issuerIdentifier="https://auth.inst1.apps.npos2.ecifmdev.net/oidc/endpoint/MaximoAppSuite"
 * #   → Liberty talks to Core IDP, NOT the bridge!
 *
 * ─────────────────────────────────────────────────────────────────
 * Exercise 5: Trace the redirect chain
 * ─────────────────────────────────────────────────────────────────
 *
 * # Step 1: Hit TRIRIGA (will redirect to Liberty)
 * curl -skv "https://main.facilities.inst1.apps.npos2.ecifmdev.net/login"
 *
 * # Step 2: Follow the 302 (will go to Core IDP)
 * curl -skv "https://auth.inst1.apps.npos2.ecifmdev.net/oidc/endpoint/MaximoAppSuite/authorize?response_type=code&..."
 *
 * # Step 3: If bridge is configured as upstream, next redirect goes to bridge
 * curl -skv "https://ecifm-sso-bridge-ns.apps.npos2.ecifmdev.net/oauth2/authorize?..."
 *
 * ─────────────────────────────────────────────────────────────────
 * Exercise 6: Entity managers (15+ pods in mas-inst1-core)
 * ─────────────────────────────────────────────────────────────────
 *
 * oc get pods -n mas-inst1-core | findstr "entitymgr"
 *
 * # Each entity manager handles a specific configuration domain:
 * #   entitymgr-db      → Database config (JDBC, datasources)
 * #   entitymgr-mongo   → MongoDB connections
 * #   entitymgr-kafka   → Kafka/event streams
 * #   entitymgr-iot     → IoT integration
 * #   entitymgr-report  → Reporting config
 * #   etc.
 *
 * # They are started by the MAS installer (ansible-devops)
 * # and auto-configure each component.
 *
 * ─────────────────────────────────────────────────────────────────
 * Exercise 7: MAS Internal API (mTLS)
 * ─────────────────────────────────────────────────────────────────
 *
 * # TRIRIGA validates credentials by calling the internal API:
 * #   POST https://internalapi.mas-inst1-core.svc:443/v3/users/checkauthentication
 * #
 * # This call uses mutual TLS (mTLS) — both sides present certs.
 * # The certs are stored in secrets:
 * #
 * #   inst1-main-credentials-truststore
 * #   inst1-main-credentials-keystore
 * #
 * # You can test mTLS with curl if you extract the certs:
 * # curl -sk --cert client.crt --key client.key \
 * #   https://internalapi.mas-inst1-core.svc:443/v3/users/checkauthentication
 *
 * ─────────────────────────────────────────────────────────────────
 * The Critical Discovery (from the real investigation)
 * ─────────────────────────────────────────────────────────────────
 *
 * Liberty's oidc.xml contains:
 *   <openidConnectClient
 *     issuerIdentifier="https://auth.inst1.apps.npos2.ecifmdev.net/oidc/endpoint/MaximoAppSuite"
 *     ...
 *   />
 *
 * This means:
 *   TRIRIGA Liberty → Core IDP (auth.inst1...) → Bridge → Entra ID
 *                    ↑                        ↑
 *               NOT the bridge            THIS is where
 *               directly!                  the bridge feeds in
 *
 * The bridge is one of Core IDP's upstream OIDC providers,
 * NOT TRIRIGA Liberty's direct OIDC provider.
 *
 * This is why generating auth codes from the bridge and posting
 * them to Liberty will NEVER work — Liberty validates codes
 * against Core IDP's token endpoint, not the bridge's.
 */
public class MasCheatsheet {
    // This class is intentionally empty — it's a documentation file
}
