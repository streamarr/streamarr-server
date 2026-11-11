package com.streamarr.server.services.events.auth;

import com.streamarr.server.domain.auth.ChangedShare;
import com.streamarr.server.domain.auth.ShareAction;
import lombok.NonNull;

/**
 * A write changed one Profile share, or is about to delete it with its Profile or its Household. It
 * is published inside the writer's transaction, so a listener after commit hears only changes that
 * committed, and a listener inside the transaction commits or rolls back with the write. {@code
 * live} says whether the share was pending or active when the change was made: still is, or was
 * until this change. The target Household's HouseholdAdmins follow a share only while it is live,
 * so they hear the change that ends it and none after. {@code removal} says the change is the
 * share's deletion, so a share deleted while live reaches them as removed. {@code action} says what
 * the write did that the share's sides may be told about, and which Account did it.
 */
public record ProfileShareChangedEvent(
    @NonNull ChangedShare share, boolean live, boolean removal, @NonNull ShareAction action) {

  /** A change after which the share is still stored, and that tells neither side anything. */
  public ProfileShareChangedEvent(ChangedShare share, boolean live) {
    this(share, live, false, new ShareAction.Other());
  }

  /** A change after which the share is still stored. */
  public ProfileShareChangedEvent(ChangedShare share, boolean live, ShareAction action) {
    this(share, live, false, action);
  }

  /** A change that tells neither side of the share anything: its deletion when {@code removal}. */
  public ProfileShareChangedEvent(ChangedShare share, boolean live, boolean removal) {
    this(share, live, removal, new ShareAction.Other());
  }

  /** Whether this change ended the share: it was pending or active until this change. */
  public boolean ended() {
    return live && !share.status().isLive();
  }
}
