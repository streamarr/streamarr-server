package com.streamarr.server.graphql.mutation.identity.lifecycle;

import com.streamarr.server.graphql.mutation.InputMutationError;
import java.util.List;

public record ConfirmationRequiredError(String message, List<String> inputPath)
    implements DeleteMyAccountError, InputMutationError {}
