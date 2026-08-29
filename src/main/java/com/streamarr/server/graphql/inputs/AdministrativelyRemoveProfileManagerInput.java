package com.streamarr.server.graphql.inputs;

public record AdministrativelyRemoveProfileManagerInput(
    String profileId, String accountId, String reason) {}
