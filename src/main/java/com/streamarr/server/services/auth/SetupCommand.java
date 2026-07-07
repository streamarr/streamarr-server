package com.streamarr.server.services.auth;

import lombok.Builder;

@Builder
public record SetupCommand(
    String email, String displayName, String password, String householdName, String profileName) {}
