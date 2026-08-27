package com.streamarr.server.graphql.inputs;

import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.ProfileKind;

public record IssueAccountInvitationInput(
    String recipientEmail,
    String householdId,
    HouseholdRole householdRole,
    String profileName,
    ProfileKind profileKind,
    Integer maximumAllowedRatingAge,
    String profileManagerAccountId) {}
