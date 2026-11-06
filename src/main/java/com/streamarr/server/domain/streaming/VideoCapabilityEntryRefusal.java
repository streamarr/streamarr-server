package com.streamarr.server.domain.streaming;

import java.util.List;
import lombok.NonNull;

/**
 * What one video capability entry refused of a delivery that the decision checked (#396). {@code
 * entryPosition} is the entry's position, from 0, among the declaration's video capability entries.
 * {@code reasons} names each refused check, in the order the entry checks them, and is empty when
 * the entry accepted the delivery.
 */
public record VideoCapabilityEntryRefusal(
    @NonNull CapabilityEntryDelivery delivery,
    int entryPosition,
    @NonNull List<DecisionReason> reasons) {

  public VideoCapabilityEntryRefusal {
    reasons = List.copyOf(reasons);
  }
}
