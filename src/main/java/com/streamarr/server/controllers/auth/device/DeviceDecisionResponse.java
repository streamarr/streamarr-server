package com.streamarr.server.controllers.auth.device;

/** Reports the stored grant decision, which can differ from the requested decision. */
public record DeviceDecisionResponse(String status, String deviceName) {}
