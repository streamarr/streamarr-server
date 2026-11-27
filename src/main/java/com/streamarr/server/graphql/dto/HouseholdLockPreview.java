package com.streamarr.server.graphql.dto;

import com.streamarr.server.services.identity.HouseholdLocks;
import java.util.List;

public record HouseholdLockPreview(
    HouseholdSummary household, List<LockedProfile> profiles, boolean locksThisProfile) {

  public static HouseholdLockPreview from(HouseholdLocks locks) {
    return new HouseholdLockPreview(
        new HouseholdSummary(locks.household().getId(), locks.household().getName()),
        locks.profiles().stream().map(LockedProfile::from).toList(),
        locks.locksThisProfile());
  }
}
