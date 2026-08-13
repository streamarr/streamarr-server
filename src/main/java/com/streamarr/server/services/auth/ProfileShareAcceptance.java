package com.streamarr.server.services.auth;

import java.util.UUID;
import lombok.Builder;

@Builder
public record ProfileShareAcceptance(
    UUID actingAccountId, UUID shareId, UUID managementInvitationId) {}
