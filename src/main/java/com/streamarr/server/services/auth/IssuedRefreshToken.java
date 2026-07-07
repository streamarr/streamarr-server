package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.AuthSession;

public record IssuedRefreshToken(String rawToken, AuthSession session) {}
