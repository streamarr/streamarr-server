package com.streamarr.server.controllers.auth;

import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.services.auth.AccountInvitationService.InvitationPreview;
import java.time.Instant;
import lombok.Builder;

/** The lookup wire body: only what the code holder needs to decide, owned by the REST layer. */
@Builder
public record InvitationLookupResponse(
    String recipientEmail,
    String householdName,
    HouseholdRole householdRole,
    String profileName,
    ProfileKind profileKind,
    Integer maximumAllowedRatingAge,
    Instant expiresAt) {

  public static InvitationLookupResponse from(InvitationPreview preview) {
    return InvitationLookupResponse.builder()
        .recipientEmail(preview.recipientEmail())
        .householdName(preview.householdName())
        .householdRole(preview.householdRole())
        .profileName(preview.profileName())
        .profileKind(preview.profileKind())
        .maximumAllowedRatingAge(preview.maximumAllowedRatingAge())
        .expiresAt(preview.expiresAt())
        .build();
  }
}
