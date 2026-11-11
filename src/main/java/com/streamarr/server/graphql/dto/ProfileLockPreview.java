package com.streamarr.server.graphql.dto;

import com.streamarr.server.services.identity.HouseholdLocks;
import java.util.List;

public record ProfileLockPreview(List<HouseholdLockPreview> households) {

  public static ProfileLockPreview from(List<HouseholdLocks> locks) {
    return new ProfileLockPreview(locks.stream().map(HouseholdLockPreview::from).toList());
  }
}
