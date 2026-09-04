package com.streamarr.server.graphql.inputs;

import com.streamarr.server.services.identity.AccountLifecycleService.ProfileDisposition;

public record AdministrativelyDeleteAccountInput(
    String accountId,
    ProfileDisposition profileDisposition,
    String replacementManagerAccountId,
    String reason) {}
