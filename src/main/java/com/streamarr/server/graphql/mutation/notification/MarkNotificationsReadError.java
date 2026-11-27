package com.streamarr.server.graphql.mutation.notification;

import com.streamarr.server.graphql.mutation.InputMutationError;
import java.util.List;

/** The {@code MarkNotificationsReadError} union; record names are the schema type names. */
public sealed interface MarkNotificationsReadError extends InputMutationError {

  record NotificationIdCountOutOfRangeError(String message, List<String> inputPath)
      implements MarkNotificationsReadError {}

  record InvalidIdError(String message, List<String> inputPath)
      implements MarkNotificationsReadError {}
}
