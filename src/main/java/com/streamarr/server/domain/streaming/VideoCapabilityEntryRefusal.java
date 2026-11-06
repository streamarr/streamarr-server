package com.streamarr.server.domain.streaming;

import java.util.List;
import lombok.NonNull;

/**
 * What one video capability entry refused of the best copy that the decision could plan (#396).
 * {@code entryPosition} is the entry's position, from 0, among the declaration's video capability
 * entries. {@code reasons} names each refused property, in the order the entry checks them, and is
 * empty when the entry accepted every property of that copy.
 */
public record VideoCapabilityEntryRefusal(
    int entryPosition, @NonNull List<DecisionReason> reasons) {

  public VideoCapabilityEntryRefusal {
    reasons = List.copyOf(reasons);
  }
}
