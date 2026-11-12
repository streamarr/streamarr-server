package com.streamarr.server.graphql.dto;

import com.streamarr.server.services.identity.ProfileLocks;
import java.util.List;

public record ProfileLockPreview(
    boolean locksThisProfile,
    List<HouseholdLockPreview> households,
    boolean otherProfilesWillLock) {

  public static ProfileLockPreview from(ProfileLocks locks) {
    return new ProfileLockPreview(
        locks.locksThisProfile(),
        locks.households().stream().map(HouseholdLockPreview::from).toList(),
        locks.otherProfilesWillLock());
  }
}
