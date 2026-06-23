package com.ecifm.saml.bridge.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/local")
public class LocalMockController {

    private static final Logger log = LoggerFactory.getLogger(LocalMockController.class);

    @Value("${mas.base-url}")
    private String masBaseUrl;

    @GetMapping("/mock-sso")
    @ResponseBody
    public ResponseEntity<String> mockSsoConnect(
            @RequestParam(value = "userName", required = false, defaultValue = "local.user@ecifm.com") String userName,
            @RequestParam(value = "adGroupName", required = false, defaultValue = "LOCAL_TEST_GROUP") String adGroupName,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        log.info("Local mock SSOConnect called for userName: {} adGroupName: {}", userName, adGroupName);

        if (authHeader != null) {
            log.info("Authorization header present: {}", authHeader.substring(0, Math.min(20, authHeader.length())) + "...");
        }

        return ResponseEntity.ok("Local mock SSOConnect executed successfully");
    }

    @GetMapping("/mock-redirect")
    @ResponseBody
    public ResponseEntity<String> mockRedirect(
            @RequestParam(value = "userName", required = false, defaultValue = "local.user@ecifm.com") String userName,
            @RequestParam(value = "groups", required = false, defaultValue = "LOCAL_TEST_GROUP") String groups) {

        log.info("Local mock redirect for user: {} groups: {}", userName, groups);
        return ResponseEntity.ok("Mock redirect to: " + masBaseUrl + "/tririga");
    }
}
