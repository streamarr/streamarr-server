package com.streamarr.server.fakes;

import com.streamarr.server.services.auth.ProfileSelectionCleaner;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class FakeProfileSelectionCleaner implements ProfileSelectionCleaner {

  public final List<ClearedSelection> clearedSelections = new ArrayList<>();

  @Override
  public int clear(UUID profileId, UUID householdId) {
    clearedSelections.add(new ClearedSelection(profileId, householdId));
    return 1;
  }

  public record ClearedSelection(UUID profileId, UUID householdId) {}
}
