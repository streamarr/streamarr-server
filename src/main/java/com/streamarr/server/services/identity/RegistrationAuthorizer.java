package com.streamarr.server.services.identity;

import java.util.UUID;
import lombok.NonNull;

/** The Account that authorized a Device registration, in the Household it registered to. */
public record RegistrationAuthorizer(@NonNull UUID householdId, @NonNull UUID accountId) {}
