package com.streamarr.server.services.identity;

import java.util.UUID;
import lombok.NonNull;

/** One Household's Devices, all of them or only those its visitors linked. */
public record HouseholdDevicesScope(@NonNull UUID householdId, boolean visitorsOnly) {}
