package com.streamarr.server.graphql.inputs;

import com.streamarr.server.services.identity.AccountLifecycleService.SourceAccess;

public record TransferAccountInput(
    String accountId, String destinationHouseholdId, SourceAccess sourceAccess, String reason) {}
