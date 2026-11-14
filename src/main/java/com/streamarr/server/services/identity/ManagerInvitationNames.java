package com.streamarr.server.services.identity;

import lombok.Builder;
import lombok.NonNull;

/** The Profile's name and the inviter's display name that a manager invitation shows. */
@Builder
public record ManagerInvitationNames(
    @NonNull String profileName, @NonNull String inviterDisplayName) {}
