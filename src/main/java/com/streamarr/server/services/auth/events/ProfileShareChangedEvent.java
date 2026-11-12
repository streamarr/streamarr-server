package com.streamarr.server.services.auth.events;

import com.streamarr.server.domain.auth.ChangedShare;
import lombok.NonNull;

/**
 * A write changed one Profile share, or is about to delete it with its Profile or its Household. It
 * is published inside the writer's transaction, so a listener after commit hears only changes that
 * committed. {@code live} says whether the share was pending or active when the change was made:
 * still is, or was until this change. The target Household's HouseholdAdmins follow a share only
 * while it is live, so they hear the change that ends it and none after. {@code removal} says the
 * change is the share's deletion, so a share deleted while live reaches them as removed.
 */
public record ProfileShareChangedEvent(@NonNull ChangedShare share, boolean live, boolean removal) {

  /** A change after which the share is still stored. */
  public ProfileShareChangedEvent(@NonNull ChangedShare share, boolean live) {
    this(share, live, false);
  }

  /** Whether this change ended the share: it was pending or active until this change. */
  public boolean ended() {
    return live && !share.status().isLive();
  }
}
