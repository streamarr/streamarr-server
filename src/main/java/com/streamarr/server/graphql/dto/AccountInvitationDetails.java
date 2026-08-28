package com.streamarr.server.graphql.dto;

import com.streamarr.server.domain.auth.AccountInvitation;
import com.streamarr.server.domain.auth.AccountInvitationStatus;
import com.streamarr.server.domain.auth.HouseholdRole;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

/**
 * The ServerAdmin-facing projection of an invitation: target, Profile shape, projected status, and
 * expiry. The public id and secret digest are never exposed.
 */
@Builder
public record AccountInvitationDetails(
    UUID id,
    String recipientEmail,
    UUID householdId,
    String householdName,
    HouseholdRole householdRole,
    AccountInvitationProfile profile,
    AccountInvitationStatus status,
    String expiresAt) {

  public static AccountInvitationDetails from(AccountInvitation invitation, Instant now) {
    return AccountInvitationDetails.builder()
        .id(invitation.getId())
        .recipientEmail(invitation.getRecipientEmail())
        .householdId(invitation.getHouseholdId())
        .householdName(invitation.getHouseholdName())
        .householdRole(invitation.getHouseholdRole())
        .profile(profile(invitation))
        .status(invitation.statusAt(now))
        .expiresAt(invitation.getExpiresAt().toString())
        .build();
  }

  private static AccountInvitationProfile profile(AccountInvitation invitation) {
    return switch (invitation.getMode()) {
      case CREATE ->
          new NewAccountInvitationProfile(
              invitation.getProfileName(),
              invitation.getProfileKind(),
              invitation.getMaximumAllowedRatingAge());
      case LINK ->
          new ExistingAccountInvitationProfile(
              invitation.getProfileId(),
              invitation.getProfileName(),
              invitation.getProfileKind(),
              invitation.getMaximumAllowedRatingAge(),
              invitation.getProfileId() == null);
    };
  }
}
