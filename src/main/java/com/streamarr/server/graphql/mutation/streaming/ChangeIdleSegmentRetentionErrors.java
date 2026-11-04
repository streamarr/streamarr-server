package com.streamarr.server.graphql.mutation.streaming;

import com.streamarr.server.graphql.mutation.InputPath;
import com.streamarr.server.services.serversettings.ChangeIdleSegmentRetentionRejection;
import com.streamarr.server.services.serversettings.IdleSegmentRetentionSetting;

/** The exhaustive mapping from service rejection to schema error type. */
public final class ChangeIdleSegmentRetentionErrors {

  private ChangeIdleSegmentRetentionErrors() {}

  public static ChangeIdleSegmentRetentionError toError(
      ChangeIdleSegmentRetentionRejection rejection) {
    return switch (rejection) {
      case ChangeIdleSegmentRetentionRejection.OutOfRange _ ->
          new ChangeIdleSegmentRetentionError.IdleSegmentRetentionOutOfRangeError(
              "Choose from %d to %d minutes."
                  .formatted(
                      IdleSegmentRetentionSetting.MINIMUM_MINUTES,
                      IdleSegmentRetentionSetting.MAXIMUM_MINUTES),
              InputPath.of("minutes"));
    };
  }
}
