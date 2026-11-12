package com.streamarr.server.graphql.dto;

import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

/**
 * A manager invitation as its readers see it: never its secret, and never whether an Account can
 * answer it. Only its named Account reads it as addressed to itself.
 */
@Builder
public record ManagerInvitationDetails(
    UUID id,
    UUID profileId,
    String profileName,
    UUID inviterAccountId,
    String inviterDisplayName,
    String recipientEmail,
    ProfileManagerInvitationStatus status,
    String expiresAt,
    boolean addressedToReader) {

  /** The invitation as the Profile's managers read it, for the reader with the Account id. */
  public static ManagerInvitationDetails from(
      ProfileManagerInvitation invitation, Instant now, UUID readerAccountId) {
    return from(invitation, invitation.statusAt(now), readerAccountId);
  }

  /** The invitation with the status that the reader with the Account id reads. */
  public static ManagerInvitationDetails from(
      ProfileManagerInvitation invitation,
      ProfileManagerInvitationStatus status,
      UUID readerAccountId) {
    return ManagerInvitationDetails.builder()
        .id(invitation.getId())
        .profileId(invitation.getProfileId())
        .profileName(invitation.getProfileName())
        .inviterAccountId(invitation.getInviterAccountId())
        .inviterDisplayName(invitation.getInviterDisplayName())
        .recipientEmail(invitation.getRecipientEmail())
        .status(status)
        .expiresAt(invitation.getExpiresAt().toString())
        .addressedToReader(invitation.isAddressedTo(readerAccountId))
        .build();
  }
}
