package com.streamarr.server.graphql.mutation.devices;

import com.streamarr.server.graphql.mutation.InputMutationError;
import java.util.List;

public record RegistrationNotFoundError(String message, List<String> inputPath)
    implements RevokeDeviceRegistrationError, InputMutationError {}
