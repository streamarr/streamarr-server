package com.streamarr.server.graphql.inputs;

import com.streamarr.server.domain.auth.HouseholdRole;
import java.util.List;

public record IssueAccountInvitationForExistingProfileInput(
    String recipientEmail,
    String profileId,
    HouseholdRole householdRole,
    List<String> reofferHouseholdIds) {}
