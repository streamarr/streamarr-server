package com.streamarr.server.graphql.cursor;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.streamarr.server.services.pagination.CursorSortValues;
import com.streamarr.server.services.pagination.Position;
import com.streamarr.server.services.pagination.SortValue;
import java.util.UUID;
import lombok.NonNull;

/** The JSON a position cursor carries, before Base64 encoding. */
record PositionCursorPayload(
    int version,
    @NonNull String list,
    @NonNull String scope,
    @NonNull ValueType type,
    @JsonInclude(JsonInclude.Include.NON_NULL) String value,
    @NonNull UUID item) {

  enum ValueType {
    TEXT,
    INSTANT,
    DATE,
    NUMBER,
    ABSENT
  }

  static PositionCursorPayload of(int version, String list, String scope, Position position) {
    return switch (position.value()) {
      case SortValue.OfText(var text) ->
          new PositionCursorPayload(version, list, scope, ValueType.TEXT, text, position.itemId());
      case SortValue.OfInstant(var instant) ->
          new PositionCursorPayload(
              version, list, scope, ValueType.INSTANT, instant.toString(), position.itemId());
      case SortValue.OfDate(var date) ->
          new PositionCursorPayload(
              version, list, scope, ValueType.DATE, date.toString(), position.itemId());
      case SortValue.OfNumber(var number) ->
          new PositionCursorPayload(
              version, list, scope, ValueType.NUMBER, Long.toString(number), position.itemId());
      case SortValue.Absent _ ->
          new PositionCursorPayload(
              version, list, scope, ValueType.ABSENT, null, position.itemId());
    };
  }

  /**
   * @throws IllegalArgumentException when the value cannot be read as its type
   * @throws NullPointerException when a type other than ABSENT carries no value
   */
  Position position() {
    var sortValue =
        switch (type) {
          case TEXT -> new SortValue.OfText(value);
          case INSTANT -> new SortValue.OfInstant(CursorSortValues.readInstant(value));
          case DATE -> new SortValue.OfDate(CursorSortValues.readDate(value));
          case NUMBER -> new SortValue.OfNumber(Long.parseLong(value));
          case ABSENT -> new SortValue.Absent();
        };
    return new Position(sortValue, item);
  }
}
