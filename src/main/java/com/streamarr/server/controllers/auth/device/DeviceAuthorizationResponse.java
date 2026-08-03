package com.streamarr.server.controllers.auth.device;

import java.time.Instant;
import lombok.Builder;

/** The lookup view: never carries the device code. */
@Builder
public record DeviceAuthorizationResponse(
    String userCode, String deviceName, String status, Instant requestedAt) {}
