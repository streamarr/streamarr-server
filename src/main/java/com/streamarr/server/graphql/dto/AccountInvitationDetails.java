package com.streamarr.server.graphql.dto;

import com.streamarr.server.domain.auth.AccountInvitation;
import com.streamarr.server.domain.auth.AccountInvitationMode;
import com.streamarr.server.domain.auth.AccountInvitationStatus;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.ProfileKind;
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
    AccountInvitationMode mode,
    UUID profileId,
    String profileName,
    ProfileKind profileKind,
    Integer maximumAllowedRatingAge,
    AccountInvitationStatus status,
    String expiresAt) {

  public static AccountInvitationDetails from(AccountInvitation invitation, Instant now) {
    return AccountInvitationDetails.builder()
        .id(invitation.getId())
        .recipientEmail(invitation.getRecipientEmail())
        .householdId(invitation.getHouseholdId())
        .householdName(invitation.getHouseholdName())
        .householdRole(invitation.getHouseholdRole())
        .mode(invitation.getMode())
        .profileId(invitation.getProfileId())
        .profileName(invitation.getProfileName())
        .profileKind(invitation.getProfileKind())
        .maximumAllowedRatingAge(invitation.getMaximumAllowedRatingAge())
        .status(invitation.statusAt(now))
        .expiresAt(invitation.getExpiresAt().toString())
        .build();
  }
}
