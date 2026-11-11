package com.streamarr.server.graphql.mutation.notification;

import com.streamarr.server.graphql.mutation.InputMutationError;
import java.util.List;

/** The {@code MarkNotificationReadError} union; record names are the schema type names. */
public sealed interface MarkNotificationReadError extends InputMutationError {

  record NotificationNotFoundError(String message, List<String> inputPath)
      implements MarkNotificationReadError {}

  record InvalidIdError(String message, List<String> inputPath)
      implements MarkNotificationReadError {}
}
