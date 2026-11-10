package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;

/**
 * One committed change of a share as a reader may read it: the share as it stands now, or no share
 * once it was deleted with its Profile or its Household.
 */
public record ShareChange(@NonNull UUID shareId, @NonNull Optional<ProfileHouseholdShare> share) {}
