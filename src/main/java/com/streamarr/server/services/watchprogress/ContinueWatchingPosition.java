package com.streamarr.server.services.watchprogress;

import com.streamarr.server.domain.streaming.WatchStatus;
import com.streamarr.server.services.pagination.MediaOrder;
import com.streamarr.server.services.pagination.MediaOrderKey;
import java.time.Instant;
import java.util.UUID;
import lombok.NonNull;

public record ContinueWatchingPosition(boolean matchesFilter, String orderKey) {
  public static ContinueWatchingPosition from(
      @NonNull UUID itemId, @NonNull WatchStatus watchStatus, Instant lastWatchedAt) {
    if (watchStatus != WatchStatus.IN_PROGRESS) {
      return new ContinueWatchingPosition(false, null);
    }

    return new ContinueWatchingPosition(
        true, MediaOrderKey.encode(MediaOrder.newestWatchedFirst(itemId, lastWatchedAt)));
  }
}
