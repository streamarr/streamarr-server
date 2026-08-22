package com.streamarr.server.graphql.dto;

import com.streamarr.server.domain.auth.AccountInvitation;
import com.streamarr.server.domain.auth.AccountInvitationMode;
import com.streamarr.server.domain.auth.AccountInvitationStatus;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.ProfileKind;
import java.util.UUID;
import lombok.Builder;

/** Everything about an invitation except its secret, which is never queryable. */
@Builder
public record AccountInvitationView(
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

  public static AccountInvitationView from(AccountInvitation invitation) {
    return AccountInvitationView.builder()
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
        .status(invitation.getStatus())
        .expiresAt(invitation.getExpiresAt().toString())
        .build();
  }
}
