package com.streamarr.server.domain.media;

import lombok.NonNull;

/**
 * The status a media file ends in when matching stops short of a match, with the reason when the
 * status alone does not explain it.
 */
public record MatchingFailure(@NonNull MediaFileStatus status, ItemFailureReason reason) {

  public MatchingFailure {
    if (status == MediaFileStatus.MATCHED || status == MediaFileStatus.UNMATCHED) {
      throw new IllegalArgumentException("A matching failure cannot end in " + status);
    }
  }

  public static MatchingFailure of(@NonNull MediaFileStatus status) {
    return new MatchingFailure(status, null);
  }
}
