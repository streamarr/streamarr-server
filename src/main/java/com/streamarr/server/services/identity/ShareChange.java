package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.ChangedShare;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;

/**
 * One committed change of a share as a reader may read it: the share as it stands now, or no share
 * once it was deleted with its Profile or its Household.
 */
public record ShareChange(@NonNull UUID shareId, @NonNull Optional<ProfileHouseholdShare> share) {

  /**
   * The change that ended a share as it was made: the share's id, Profile, Household and the status
   * the change left it in, and nothing else. It does not read the share again, so nothing that
   * happens to the share or its Profile after the change reaches whoever gets it.
   */
  public static ShareChange endedAs(ChangedShare ended) {
    return new ShareChange(
        ended.shareId(),
        Optional.of(
            ProfileHouseholdShare.builder()
                .id(ended.shareId())
                .profileId(ended.profileId())
                .householdId(ended.householdId())
                .status(ended.status())
                .build()));
  }
}
