package com.streamarr.server.domain.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * A manager invitation that one write changed, with its parties, the status the write left it in
 * and when it expires. The named Account is absent when no Account could answer the invitation.
 */
@Builder(toBuilder = true)
public record ChangedManagerInvitation(
    @NonNull UUID invitationId,
    @NonNull UUID profileId,
    @NonNull UUID inviterAccountId,
    @NonNull Optional<UUID> recipientAccountId,
    @NonNull ProfileManagerInvitationStatus status,
    @NonNull Instant expiresAt) {

  public static ChangedManagerInvitation of(ProfileManagerInvitation invitation) {
    return builder()
        .invitationId(invitation.getId())
        .profileId(invitation.getProfileId())
        .inviterAccountId(invitation.getInviterAccountId())
        .recipientAccountId(Optional.ofNullable(invitation.getRecipientAccountId()))
        .status(invitation.getStatus())
        .expiresAt(invitation.getExpiresAt())
        .build();
  }
}
