package com.streamarr.server.services.auth;

import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record ProfileManagerInvite(
    @NonNull UUID actingAccountId, @NonNull UUID invitedAccountId, @NonNull UUID profileId) {}
