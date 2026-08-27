package com.streamarr.server.graphql.mutation.sharing;

import com.streamarr.server.graphql.mutation.InputMutationError;
import java.util.List;

public record ShareNotPendingError(String message, List<String> inputPath)
    implements AcceptProfileShareError,
        CancelProfileShareError,
        RejectProfileShareError,
        InputMutationError {}
