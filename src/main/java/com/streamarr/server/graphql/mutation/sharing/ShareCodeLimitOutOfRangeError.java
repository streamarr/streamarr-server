package com.streamarr.server.graphql.mutation.sharing;

import com.streamarr.server.graphql.mutation.InputMutationError;
import java.util.List;

public record ShareCodeLimitOutOfRangeError(String message, List<String> inputPath)
    implements ChangeShareCodeLimitError, InputMutationError {}
