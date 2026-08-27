package com.streamarr.server.graphql.mutation.sharing;

public record MembershipShareCannotEndError(String message)
    implements EndProfileShareError, AdministrativelyEndProfileShareError {}
