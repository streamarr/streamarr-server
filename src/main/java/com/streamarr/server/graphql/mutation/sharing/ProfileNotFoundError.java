package com.streamarr.server.graphql.mutation.sharing;

import com.streamarr.server.graphql.mutation.InputMutationError;
import java.util.List;

public record ProfileNotFoundError(String message, List<String> inputPath)
    implements OfferProfileShareError, InputMutationError {}
