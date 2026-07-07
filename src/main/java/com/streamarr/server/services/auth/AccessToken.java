package com.streamarr.server.services.auth;

import java.time.Instant;
import lombok.Builder;

@Builder
public record AccessToken(String value, Instant expiresAt, TokenScope scope) {}
