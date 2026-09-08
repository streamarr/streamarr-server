package com.streamarr.server.graphql.inputs;

import com.streamarr.server.domain.auth.SourceHouseholdAccess;

public record TransferAccountInput(
    String accountId,
    String destinationHouseholdId,
    SourceHouseholdAccess sourceHouseholdAccess,
    String reason) {}
