package com.streamarr.server.graphql.mutation.devices;

import com.streamarr.server.graphql.mutation.InputMutationError;
import java.util.List;

public record EsnBlockNotFoundError(String message, List<String> inputPath)
    implements UnblockEsnError, UnblockEsnServerWideError, InputMutationError {}
