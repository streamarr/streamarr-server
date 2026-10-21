package com.streamarr.server.services.pagination;

public enum OrderMediaBy {
  TITLE,
  ADDED,
  RELEASE_DATE,
  RUNTIME,
  LAST_WATCHED;

  /**
   * Reads a sort value carried by a cursor as this sort's own type: a String title, an Instant
   * time, a LocalDate release date or an Integer runtime. A null value stays null.
   *
   * @throws IllegalArgumentException when the value cannot be read as that type or lies outside the
   *     range the database stores
   */
  public Object readCursorValue(Object value) {
    if (value == null) {
      return null;
    }

    return switch (this) {
      case TITLE -> readTitle(value);
      case ADDED, LAST_WATCHED -> CursorSortValues.readInstant(value.toString());
      case RELEASE_DATE -> CursorSortValues.readDate(value.toString());
      case RUNTIME -> Integer.valueOf(value.toString());
    };
  }

  private static String readTitle(Object value) {
    if (value instanceof String title) {
      return title;
    }

    throw new IllegalArgumentException("A title sort value must be text.");
  }
}
