package com.streamarr.server.graphql.inputs;

import com.streamarr.server.domain.auth.AccountInvitationMode;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.ProfileKind;
import java.util.List;

public record IssueAccountInvitationInput(
    String recipientEmail,
    String householdId,
    HouseholdRole householdRole,
    AccountInvitationMode mode,
    String profileId,
    List<String> reofferHouseholdIds,
    String profileName,
    ProfileKind profileKind,
    Integer maximumAllowedRatingAge,
    String localManagerAccountId) {}
