package com.streamarr.server.graphql;

import com.streamarr.server.exceptions.InvalidIdException;
import java.util.UUID;

/** GraphQL IDs arrive as strings. Malformed IDs produce request errors. */
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
