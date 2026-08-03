package com.streamarr.server.services.auth;

import java.util.UUID;
import lombok.Builder;

@Builder
public record DeviceDecisionCommand(
    String userCode, DeviceDecision decision, UUID decidedByAccountId) {}
