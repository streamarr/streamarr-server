package com.streamarr.server.graphql.mutation.devices;

import java.util.List;

public record RevokeDeviceRegistrationPayload(
    String registrationId, List<RevokeDeviceRegistrationError> userErrors) {}
