package com.streamarr.server.graphql.mutation.notification;

import com.streamarr.server.graphql.mutation.InputMutationError;
import java.util.List;

/** The {@code MarkAllNotificationsReadError} union; record names are the schema type names. */
public sealed interface MarkAllNotificationsReadError extends InputMutationError {

  record InvalidCursorError(String message, List<String> inputPath)
      implements MarkAllNotificationsReadError {}
}
