package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.UserAccount;
import lombok.Builder;

@Builder
public record LoginResult(UserAccount account, AuthSession session, String rawRefreshToken) {}
