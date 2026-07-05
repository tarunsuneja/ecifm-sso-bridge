package com.ecifm.examples.stage7;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/*
 * ─────────────────────────────────────────────────────────────────
 * STAGE 7: Entra ID & Microsoft Graph API
 * Exercise 2: Graph API Fallback (Resolve >200 Groups)
 * ─────────────────────────────────────────────────────────────────
 *
 * Goal: When a user is in more than 200 groups, the JWT only
 *       contains the first 200. Use Graph API to resolve all groups.
 *
 * The indicator:
 *   JWT contains "_claim_names": {"groups": "src1"}
 *   JWT contains "_claim_sources": {"src1": {"endpoint": "..."}}
 *   → "You have more groups than fit in the token. Call this URL."
 *
 * This class shows how the bridge's EntraIdGroupResolver works.
 *
 * Prerequisites:
 *   - An Azure AD App Registration with:
 *     GroupMember.Read.All (delegated)
 *     User.Read (delegated)
 *   - A Bearer token from the user (with these permissions)
 *
 * Graph API call:
 *   GET https://graph.microsoft.com/v1.0/me/memberOf
 *   Authorization: Bearer <user_token>
 *
 * Response:
 *   {
 *     "value": [
 *       {"@odata.type": "#microsoft.graph.group", "id": "...", "displayName": "..."},
 *       {"@odata.type": "#microsoft.graph.directoryRole", ...}
 *     ]
 *   }
 */
public class Ex2_GraphApiFallback {

    static final HttpClient client = HttpClient.newHttpClient();

    public static void main(String[] args) throws Exception {
        System.out.println("=== Graph API: Resolve All Groups ===\n");

        // ── Step 1: Check if overage indicator exists ──
        String jwt = "{\"sub\":\"user@example.com\",\"groups\":[\"g1\",\"g2\",...200 items...],"
            + "\"_claim_names\":{\"groups\":\"src1\"},"
            + "\"_claim_sources\":{\"src1\":{\"endpoint\":\"https://graph.microsoft.com/v1.0/users/user-id/memberOf\"}}}";

        boolean hasOverage = jwt.contains("_claim_names");
        System.out.println("JWT has overage indicator: " + hasOverage);
        System.out.println("(The _claim_names field means >200 groups)");

        // ── Step 2: Call Graph API ──
        String userToken = "your-user-bearer-token-here";
        String graphUrl = "https://graph.microsoft.com/v1.0/me/memberOf";

        System.out.println("\nCalling Graph API:");
        System.out.println("  GET " + graphUrl);
        System.out.println("  Authorization: Bearer " + userToken.substring(0, 20) + "...");

        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(graphUrl))
                .header("Authorization", "Bearer " + userToken)
                .GET()
                .build();

            HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString());

            System.out.println("  Status: " + response.statusCode());
            if (response.statusCode() == 200) {
                System.out.println("  Groups: " + countGroups(response.body()) + " groups resolved");
            } else {
                System.out.println("  Error: " + response.body().substring(0, Math.min(200, response.body().length())));
            }
        } catch (Exception e) {
            System.out.println("  ERROR: " + e.getMessage());
            System.out.println("  (Expected — need a real Bearer token with GroupMember.Read.All)");
        }

        // ── Step 3: The bridge's approach ──
        System.out.println("\n=== How the Bridge Handles This ===");
        System.out.println("""
            In AcsHandlerController.java:428-444:
            
            1. After successful Entra ID login, check id_token for groups
            2. If overage indicator present (_claim_names):
               a. Get Bearer token from OAuth2AuthorizedClient
               b. Create EntraIdGroupResolver(token)
               c. Call graphClient.users(userId).memberOf().get()
               d. Filter for @odata.type=#microsoft.graph.group
               e. Return all group display names
            3. If no overage: use groups from JWT directly
            
            Key: The Graph API call uses the USER's delegated token,
            NOT a client_credentials token. This ensures the API
            returns only groups the user is a member of.
            """);
    }

    static int countGroups(String graphResponse) {
        // Simple count of "displayName" occurrences in response
        int count = 0;
        int idx = 0;
        while ((idx = graphResponse.indexOf("displayName", idx)) != -1) {
            count++;
            idx += 11;
        }
        return count;
    }
}
