package com.streamarr.server.services.auth;

import java.util.UUID;
import lombok.Builder;

@Builder
public record ProfileManagerInvitationRejection(UUID actingAccountId, UUID invitationId) {}
