package com.streamarr.server.controllers.auth;

import jakarta.validation.constraints.NotBlank;

public record SetupRequest(
    @NotBlank String email,
    @NotBlank String displayName,
    @NotBlank String password,
    @NotBlank String householdName,
    @NotBlank String profileName,
    boolean cookieMode) {}
