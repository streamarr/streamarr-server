package com.streamarr.server.controllers.auth;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
    @NotBlank String email, @NotBlank String password, String deviceName, boolean cookieMode) {}
