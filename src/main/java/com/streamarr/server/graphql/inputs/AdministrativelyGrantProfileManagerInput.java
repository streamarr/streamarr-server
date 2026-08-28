package com.streamarr.server.graphql.inputs;

public record AdministrativelyGrantProfileManagerInput(
    String profileId, String accountId, String reason) {}
