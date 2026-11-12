package com.streamarr.server.graphql.dto;

import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

/**
 * A manager invitation as its readers see it: never its secret, and never whether an Account can
 * answer it.
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
    String expiresAt) {

  /** The invitation as the Profile's managers read it. */
  public static ManagerInvitationDetails from(ProfileManagerInvitation invitation, Instant now) {
    return from(invitation, invitation.statusAt(now));
  }

  /** The invitation with the status that its reader reads. */
  public static ManagerInvitationDetails from(
      ProfileManagerInvitation invitation, ProfileManagerInvitationStatus status) {
    return ManagerInvitationDetails.builder()
        .id(invitation.getId())
        .profileId(invitation.getProfileId())
        .profileName(invitation.getProfileName())
        .inviterAccountId(invitation.getInviterAccountId())
        .inviterDisplayName(invitation.getInviterDisplayName())
        .recipientEmail(invitation.getRecipientEmail())
        .status(status)
        .expiresAt(invitation.getExpiresAt().toString())
        .build();
  }
}
