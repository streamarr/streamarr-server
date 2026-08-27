package com.streamarr.server.graphql.mutation.sharing;

public record RestrictedProfileRequiresHouseholdAdminError(String message)
    implements AcceptProfileShareError {}
