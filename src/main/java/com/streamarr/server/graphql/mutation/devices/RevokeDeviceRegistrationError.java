package com.streamarr.server.graphql.mutation.devices;

import com.streamarr.server.graphql.mutation.MutationError;

/**
 * The {@code RevokeDeviceRegistrationError} union; record names are the schema type names DGS
 * resolves by.
 */
public sealed interface RevokeDeviceRegistrationError extends MutationError
    permits RegistrationNotFoundError, RegistrationNotActiveError {}
