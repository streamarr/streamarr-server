package com.streamarr.server.graphql;

import com.streamarr.server.exceptions.InvalidIdException;
import java.util.UUID;

/** GraphQL ID scalars arrive as strings; a malformed one is a request error, never a not-found. */
public final class Ids {

  private Ids() {}

  public static UUID parseUuid(String id) {
    try {
      return UUID.fromString(id);
    } catch (IllegalArgumentException _) {
      throw new InvalidIdException(id);
    }
  }
}
