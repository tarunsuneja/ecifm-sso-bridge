package com.ecifm.examples.stage6;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import jakarta.xml.ws.BindingProvider;
import jakarta.xml.ws.handler.MessageContext;

/*
 * ─────────────────────────────────────────────────────────────────
 * STAGE 6: CXF SOAP & TRIRIGA Auth Internals
 * Exercise 1: SOAP Client with HTTP Basic Auth
 * ─────────────────────────────────────────────────────────────────
 *
 * Goal: Understand how to call a SOAP web service with:
 *   1. HTTP Basic authentication (Authorization header)
 *   2. Session tracking (cookies)
 *   3. Custom endpoint URL
 *
 * IMPORTANT: This example simulates the real TririgaWsClient
 * from the bridge. It cannot run standalone because TririgaWS
 * is generated from a WSDL via cxf-codegen-plugin.
 *
 * See pom.xml in the real bridge for the cxf-codegen-plugin config.
 *
 * ─────────────────────────────────────────────────────────────────
 * Key concepts
 * ─────────────────────────────────────────────────────────────────
 *
 * 1. WSDL → Java codegen (cxf-codegen-plugin):
 *    <plugin>
 *      <groupId>org.apache.cxf</groupId>
 *      <artifactId>cxf-codegen-plugin</artifactId>
 *      <executions>
 *        <execution>
 *          <id>generate-tririga-ws</id>
 *          <goals><goal>wsdl2java</goal></goals>
 *          <configuration>
 *            <wsdlOptions>
 *              <wsdlOption>
 *                <wsdl>${basedir}/src/main/resources/wsdl/TririgaWS.wsdl</wsdl>
 *                <extraargs><extraarg>-p</extraarg><extraarg>com.ecifm.saml.bridge.tririga.ws</extraarg></extraarg>
 *              </wsdlOption>
 *            </wsdlOptions>
 *          </configuration>
 *        </execution>
 *      </executions>
 *    </plugin>
 *
 * 2. Creating the port:
 *    TririgaWS service = new TririgaWS(wsdlUrl);
 *    TririgaWSPort port = service.getTririgaWSPort();
 *
 * 3. Setting endpoint URL:
 *    ((BindingProvider) port).getRequestContext().put(
 *        BindingProvider.ENDPOINT_ADDRESS_PROPERTY, soapEndpoint);
 *
 * 4. Setting HTTP Basic auth:
 *    String auth = "Basic " + base64(username + ":" + password);
 *    Map<String, List<String>> headers = new HashMap<>();
 *    headers.put("Authorization", Arrays.asList(auth));
 *    ((BindingProvider) port).getRequestContext().put(
 *        MessageContext.PROTOCOL_HEADERS, headers);
 *
 * 5. Session maintenance (cookies):
 *    ((BindingProvider) port).getRequestContext().put(
 *        BindingProvider.SESSION_MAINTAIN_PROPERTY, true);
 *
 * 6. Extracting JSESSIONID from response:
 *    The Set-Cookie header contains JSESSIONID=XXXXX
 *    CXF's session support auto-extracts it.
 *    To get it manually:
 *    Response response = ...; // from a SOAP call
 *    String cookie = (String) ((BindingProvider)port).getResponseContext()
 *        .get(MessageContext.HTTP_RESPONSE_HEADERS);
 *    // Parse Set-Cookie for JSESSIONID=...
 */
public class Ex1_SoapClientExample {

    // ── Simulated HTTP Basic Auth ──
    public static String buildBasicAuth(String username, String password) {
        String creds = username + ":" + password;
        String b64 = java.util.Base64.getEncoder().encodeToString(creds.getBytes());
        return "Basic " + b64;
    }

    // ── Simulated JSESSIONID extraction from response headers ──
    public static String extractJsessionId(java.util.Map<String, List<String>> responseHeaders) {
        List<String> cookies = responseHeaders.get("Set-Cookie");
        if (cookies == null) return null;
        for (String cookie : cookies) {
            // Format: "JSESSIONID=abc123; Path=/; HttpOnly"
            for (String part : cookie.split(";")) {
                part = part.trim();
                if (part.startsWith("JSESSIONID=")) {
                    return part.substring("JSESSIONID=".length());
                }
            }
        }
        return null;
    }

    // ── Simulated SOAP call (shows the pattern) ──
    public static void demonstratePattern() {
        String username = "tririga_user";
        String password = "tririga_pass";
        String soapEndpoint = "https://main.facilities.inst1.apps.npos2.ecifmdev.net/ws/TririgaWS";

        System.out.println("=== SOAP Client Pattern ===");
        System.out.println("1. Build auth header:");
        System.out.println("   Authorization: " + buildBasicAuth(username, password));

        System.out.println("\n2. Configure CXF port:");
        System.out.println("   ((BindingProvider) port).getRequestContext().put(");
        System.out.println("       BindingProvider.ENDPOINT_ADDRESS_PROPERTY, \""
            + soapEndpoint + "\");");

        System.out.println("\n3. Set auth + cookies:");
        System.out.println("   ((BindingProvider) port).getRequestContext().put(");
        System.out.println("       MessageContext.PROTOCOL_HEADERS, headers);");
        System.out.println("   ((BindingProvider) port).getRequestContext().put(");
        System.out.println("       BindingProvider.SESSION_MAINTAIN_PROPERTY, true);");

        System.out.println("\n4. Make SOAP call:");
        System.out.println("   TririgaWSPort port = ...;");
        System.out.println("   port.someSoapMethod(request);");
        System.out.println("   // CXF auto-extracts JSESSIONID from Set-Cookie");

        System.out.println("\n5. Use the session:");
        System.out.println("   String jsessionid = extractJsessionId(responseHeaders);");
        System.out.println("   System.out.println(\"JSESSIONID: \" + jsessionid);");
        System.out.println("   // Now use this cookie for subsequent REST calls");
    }

    public static void main(String[] args) {
        demonstratePattern();
    }
}
