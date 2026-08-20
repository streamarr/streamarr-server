package com.streamarr.server.graphql.mutation.lifecycle;

import com.streamarr.server.graphql.mutation.InputMutationError;
import java.util.List;

public record LocalManagerRequiredError(String message, List<String> inputPath)
    implements TransferProfileError, InputMutationError {}
