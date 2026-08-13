package com.streamarr.server.services.auth;

import java.util.UUID;
import lombok.Builder;

@Builder
public record ProfileManagerInvite(UUID actingAccountId, UUID invitedAccountId, UUID profileId) {}
