package com.streamarr.server.services.auth;

import lombok.Builder;

@Builder
public record LoginCommand(String email, String password, String deviceName, String source) {}
