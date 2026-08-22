package com.streamarr.server.graphql.mutation.sharing;

import com.streamarr.server.graphql.mutation.InputMutationError;
import java.util.List;

public record ShareNotFoundError(String message, List<String> inputPath)
    implements AcceptProfileShareError,
        CancelProfileShareError,
        EndProfileShareError,
        ForceEndProfileShareError,
        RejectProfileShareError,
        InputMutationError {}
