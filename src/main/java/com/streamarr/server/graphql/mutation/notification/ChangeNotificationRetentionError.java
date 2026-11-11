package com.streamarr.server.graphql.mutation.notification;

import com.streamarr.server.graphql.mutation.InputMutationError;
import java.util.List;

/** The {@code ChangeNotificationRetentionError} union; record names are the schema type names. */
public sealed interface ChangeNotificationRetentionError extends InputMutationError {

  record NotificationRetentionOutOfRangeError(String message, List<String> inputPath)
      implements ChangeNotificationRetentionError {}
}
