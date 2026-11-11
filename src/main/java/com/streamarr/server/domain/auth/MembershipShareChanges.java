package com.streamarr.server.domain.auth;

import java.util.List;
import lombok.NonNull;

/**
 * What making a Profile's home availability its structural share changed: the offers into that
 * Household that stopped waiting, because their time had run out or the structural share took their
 * place, and every other share it wrote. A share appears in one of the two only.
 */
public record MembershipShareChanges(
    @NonNull List<ChangedShare> offersStoppedWaiting, @NonNull List<ChangedShare> otherChanges) {

  public MembershipShareChanges {
    offersStoppedWaiting = List.copyOf(offersStoppedWaiting);
    otherChanges = List.copyOf(otherChanges);
  }
}
