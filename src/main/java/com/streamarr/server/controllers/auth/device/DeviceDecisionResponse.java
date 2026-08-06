package com.streamarr.server.controllers.auth.device;

/** Echoes the outcome that actually happened, not the one that was requested. */
public record DeviceDecisionResponse(String status, String deviceName) {}
