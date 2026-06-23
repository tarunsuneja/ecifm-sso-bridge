package com.ecifm.saml.bridge.model;

import java.util.List;

public class UserInfo {

    private String email;
    private List<String> groups;

    public UserInfo() {
    }

    public UserInfo(String email, List<String> groups) {
        this.email = email;
        this.groups = groups;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public List<String> getGroups() {
        return groups;
    }

    public void setGroups(List<String> groups) {
        this.groups = groups;
    }
}
