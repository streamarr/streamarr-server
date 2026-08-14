package com.streamarr.server.services.auth;

import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record ProfileManagerInvitationRejection(
    @NonNull UUID actingAccountId, @NonNull UUID invitationId) {}
