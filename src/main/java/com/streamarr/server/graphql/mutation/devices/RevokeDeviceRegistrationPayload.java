package com.streamarr.server.graphql.mutation.devices;

import java.util.List;
import java.util.Optional;

public record RevokeDeviceRegistrationPayload(
    Optional<String> registrationId, List<RevokeDeviceRegistrationError> userErrors) {}
