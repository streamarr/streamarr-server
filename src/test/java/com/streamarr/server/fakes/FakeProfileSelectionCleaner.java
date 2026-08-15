package com.streamarr.server.fakes;

import com.streamarr.server.services.auth.ProfileSelectionCleaner;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class FakeProfileSelectionCleaner implements ProfileSelectionCleaner {

  public final List<ClearedSelection> clearedSelections = new ArrayList<>();

  /**
   * Records the profile and household selection as cleared.
   *
   * @return {@code 1}, indicating one selection was cleared
   */
  @Override
  public int clear(UUID profileId, UUID householdId) {
    clearedSelections.add(new ClearedSelection(profileId, householdId));
    return 1;
  }

  public record ClearedSelection(UUID profileId, UUID householdId) {}
}
