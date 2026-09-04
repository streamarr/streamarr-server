package com.streamarr.server.graphql.inputs;

import com.streamarr.server.services.identity.AccountLifecycleService.ProfileCleanup;

public record AdministrativelyDeleteAccountInput(
    String accountId,
    ProfileCleanup profileCleanup,
    String replacementManagerAccountId,
    String reason) {}
