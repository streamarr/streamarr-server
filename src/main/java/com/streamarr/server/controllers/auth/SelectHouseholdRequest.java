package com.streamarr.server.controllers.auth;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record SelectHouseholdRequest(@NotNull UUID householdId, boolean cookieMode) {}
