package com.streamarr.server.graphql.mutation.lifecycle;

import com.streamarr.server.graphql.mutation.InputMutationError;
import java.util.List;

public record EligibleProfileManagerRequiredError(String message, List<String> inputPath)
    implements TransferProfileError, InputMutationError {}
