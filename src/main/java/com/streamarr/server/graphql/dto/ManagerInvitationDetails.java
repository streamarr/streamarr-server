package com.streamarr.server.graphql.dto;

import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

/** Everything about a manager invitation except its secret, which is never queryable. */
@Builder
public record ManagerInvitationDetails(
    UUID id,
    UUID profileId,
    String profileName,
    UUID inviterAccountId,
    String inviterDisplayName,
    UUID recipientAccountId,
    String recipientEmail,
    ProfileManagerInvitationStatus status,
    String expiresAt) {

  public static ManagerInvitationDetails from(ProfileManagerInvitation invitation, Instant now) {
    return ManagerInvitationDetails.builder()
        .id(invitation.getId())
        .profileId(invitation.getProfileId())
        .profileName(invitation.getProfileName())
        .inviterAccountId(invitation.getInviterAccountId())
        .inviterDisplayName(invitation.getInviterDisplayName())
        .recipientAccountId(invitation.getRecipientAccountId())
        .recipientEmail(invitation.getRecipientEmail())
        .status(invitation.statusAt(now))
        .expiresAt(invitation.getExpiresAt().toString())
        .build();
  }
}
