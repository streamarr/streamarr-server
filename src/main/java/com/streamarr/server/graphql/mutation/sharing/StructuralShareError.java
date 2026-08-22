package com.streamarr.server.graphql.mutation.sharing;

public record StructuralShareError(String message)
    implements EndProfileShareError, ForceEndProfileShareError {}
