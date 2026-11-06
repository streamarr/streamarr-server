package com.streamarr.server.graphql.mutation.streaming;

import com.streamarr.server.graphql.mutation.InputPath;
import com.streamarr.server.services.serversettings.ChangeUploadBudgetRejection;
import com.streamarr.server.services.serversettings.UploadBudgetSetting;
import java.util.Locale;

/** The exhaustive mapping from service rejection to schema error type. */
public final class ChangeUploadBudgetErrors {

  private ChangeUploadBudgetErrors() {}

  public static ChangeUploadBudgetError toError(ChangeUploadBudgetRejection rejection) {
    return switch (rejection) {
      case ChangeUploadBudgetRejection.OutOfRange _ ->
          new ChangeUploadBudgetError.UploadBudgetOutOfRangeError(
              String.format(
                  Locale.ROOT,
                  "Enter an upload speed from %d to %,d Mb/s, or turn the upload budget off.",
                  UploadBudgetSetting.MINIMUM_INTERNET_UPLOAD_MEGABITS_PER_SECOND,
                  UploadBudgetSetting.MAXIMUM_INTERNET_UPLOAD_MEGABITS_PER_SECOND),
              InputPath.of("internetUploadMegabitsPerSecond"));
    };
  }
}
