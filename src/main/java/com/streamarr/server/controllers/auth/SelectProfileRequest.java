package com.streamarr.server.controllers.auth;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record SelectProfileRequest(@NotNull UUID profileId, boolean cookieMode) {}
