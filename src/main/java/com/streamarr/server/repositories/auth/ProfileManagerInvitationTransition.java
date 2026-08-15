package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import java.util.UUID;
import lombok.Builder;

@Builder
public record ProfileManagerInvitationTransition(
    UUID invitationId,
    UUID invitedAccountId,
    UUID expectedProfileId,
    ProfileManagerInvitationStatus status) {}
