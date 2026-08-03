package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.DeviceAuthorizationStatus;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

/** The conditional decision write: which code, which outcome, decided by whom and when. */
@Builder
public record DeviceAuthorizationDecisionCommand(
    String userCode, DeviceAuthorizationStatus status, UUID decidedByAccountId, Instant now) {}
