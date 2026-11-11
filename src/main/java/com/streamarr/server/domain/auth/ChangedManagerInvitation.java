package com.streamarr.server.domain.auth;

import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;

/**
 * A manager invitation that one write changed, with its parties and the status the write left it
 * in. The named Account is absent when no Account could answer the invitation.
 */
public record ChangedManagerInvitation(
    @NonNull UUID invitationId,
    @NonNull UUID profileId,
    @NonNull UUID inviterAccountId,
    @NonNull Optional<UUID> recipientAccountId,
    @NonNull ProfileManagerInvitationStatus status) {

  public static ChangedManagerInvitation of(ProfileManagerInvitation invitation) {
    return new ChangedManagerInvitation(
        invitation.getId(),
        invitation.getProfileId(),
        invitation.getInviterAccountId(),
        Optional.ofNullable(invitation.getRecipientAccountId()),
        invitation.getStatus());
  }
}
