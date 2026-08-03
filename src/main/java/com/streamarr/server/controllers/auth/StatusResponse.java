package com.streamarr.server.controllers.auth;

/** Both fields are required in v1; a client missing either treats the server as incompatible. */
public record StatusResponse(boolean setupComplete, boolean devicePairingEnabled) {}
