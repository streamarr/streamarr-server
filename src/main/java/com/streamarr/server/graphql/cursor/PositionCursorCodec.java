package com.streamarr.server.graphql.cursor;

import com.streamarr.server.services.pagination.ListIdentity;
import com.streamarr.server.services.pagination.PageRequest;
import com.streamarr.server.services.pagination.PaginationOptions;
import com.streamarr.server.services.pagination.Position;
import com.streamarr.server.services.pagination.SortValue;
import graphql.relay.ConnectionCursor;
import graphql.relay.DefaultConnectionCursor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Encodes where an edge sits in its list as an opaque cursor, and reads it back only for the list
 * and scope that issued it. Every cursor-paginated list shares this one format.
 */
@Component
@RequiredArgsConstructor
public class PositionCursorCodec {

  private static final int VERSION = 1;
  private static final int SCOPE_FINGERPRINT_BYTES = 16;

  private final ObjectMapper objectMapper;

  public ConnectionCursor encode(ListIdentity list, Position position) {
    var payload = objectMapper.createObjectNode();
    payload.put("version", VERSION);
    payload.put("list", list.list());
    payload.put("scope", fingerprint(list));
    payload.put("type", typeOf(position.value()));
    textOf(position.value()).ifPresent(text -> payload.put("value", text));
    payload.put("item", position.itemId().toString());
    var bytes = objectMapper.writeValueAsBytes(payload);
    return new DefaultConnectionCursor(
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
  }

  /**
   * @throws InvalidCursorException when the cursor is malformed, holds an unreadable position, or
   *     belongs to another list or scope
   */
  public Position decode(ListIdentity list, String cursor) {
    var payload = readPayload(cursor);
    if (!issuedFor(list, payload)) {
      throw new InvalidCursorException("Cursor belongs to another list.");
    }

    try {
      return new Position(
          valueOf(payload.path("type").asString(), payload.get("value")),
          UUID.fromString(payload.path("item").asString()));
    } catch (RuntimeException _) {
      throw new InvalidCursorException("Cursor position cannot be read.");
    }
  }

  /**
   * The page a client asked for, continuing from the position its cursor names.
   *
   * @throws InvalidCursorException when the cursor cannot continue this list
   */
  public PageRequest pageRequest(ListIdentity list, PaginationOptions options) {
    return PageRequest.builder()
        .position(options.getCursor().map(cursor -> decode(list, cursor)))
        .direction(options.getPaginationDirection())
        .limit(options.getLimit())
        .build();
  }

  private JsonNode readPayload(String cursor) {
    try {
      var payload = objectMapper.readTree(Base64.getUrlDecoder().decode(cursor));
      if (!payload.isObject()) {
        throw new InvalidCursorException("Cursor is not valid.");
      }

      return payload;
    } catch (RuntimeException _) {
      throw new InvalidCursorException("Cursor is not valid.");
    }
  }

  private boolean issuedFor(ListIdentity list, JsonNode payload) {
    return payload.path("version").asInt() == VERSION
        && list.list().equals(payload.path("list").asString())
        && fingerprint(list).equals(payload.path("scope").asString());
  }

  private static SortValue valueOf(String type, JsonNode value) {
    if ("ABSENT".equals(type)) {
      return new SortValue.Absent();
    }

    var text = value.stringValue();
    return switch (type) {
      case "TEXT" -> new SortValue.OfText(text);
      case "INSTANT" -> new SortValue.OfInstant(SortValue.readInstant(text));
      case "DATE" -> new SortValue.OfDate(SortValue.readDate(text));
      case "NUMBER" -> new SortValue.OfNumber(Long.parseLong(text));
      default -> throw new IllegalArgumentException("Unknown sort value type " + type);
    };
  }

  private static String typeOf(SortValue value) {
    return switch (value) {
      case SortValue.OfText _ -> "TEXT";
      case SortValue.OfInstant _ -> "INSTANT";
      case SortValue.OfDate _ -> "DATE";
      case SortValue.OfNumber _ -> "NUMBER";
      case SortValue.Absent _ -> "ABSENT";
    };
  }

  private static Optional<String> textOf(SortValue value) {
    return switch (value) {
      case SortValue.OfText(var text) -> Optional.of(text);
      case SortValue.OfInstant(var instant) -> Optional.of(instant.toString());
      case SortValue.OfDate(var date) -> Optional.of(date.toString());
      case SortValue.OfNumber(var number) -> Optional.of(Long.toString(number));
      case SortValue.Absent _ -> Optional.empty();
    };
  }

  private String fingerprint(ListIdentity list) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      digest.update(list.list().getBytes(StandardCharsets.UTF_8));
      digest.update(objectMapper.writeValueAsBytes(list.scope()));
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(Arrays.copyOf(digest.digest(), SCOPE_FINGERPRINT_BYTES));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }
}
