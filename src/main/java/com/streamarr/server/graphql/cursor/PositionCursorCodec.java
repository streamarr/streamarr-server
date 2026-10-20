package com.streamarr.server.graphql.cursor;

import com.streamarr.server.services.pagination.ListIdentity;
import com.streamarr.server.services.pagination.PageRequest;
import com.streamarr.server.services.pagination.PaginationOptions;
import com.streamarr.server.services.pagination.Position;
import graphql.relay.ConnectionCursor;
import graphql.relay.DefaultConnectionCursor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

/**
 * Encodes where an edge sits in its list as an opaque cursor, and reads it back only for the list
 * and scope that issued it. Every cursor-paginated list shares this one format.
 */
@Component
public class PositionCursorCodec {

  private static final int VERSION = 1;
  private static final int SCOPE_FINGERPRINT_BYTES = 16;

  private final ObjectMapper objectMapper;
  private final ObjectReader payloadReader;

  public PositionCursorCodec(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
    this.payloadReader =
        objectMapper
            .readerFor(PositionCursorPayload.class)
            .with(
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
  }

  public ConnectionCursor encode(ListIdentity list, Position position) {
    var payload = PositionCursorPayload.of(VERSION, list.list(), fingerprint(list), position);
    return new DefaultConnectionCursor(
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(objectMapper.writeValueAsBytes(payload)));
  }

  /**
   * Reads a cursor a client sent. Decoding is total: any cursor either yields a position or fails
   * with {@link InvalidCursorException}, whatever step rejects it.
   *
   * @throws InvalidCursorException when the cursor is malformed, holds an unreadable position, or
   *     belongs to another list or scope
   */
  public Position decode(ListIdentity list, String cursor) {
    try {
      return readPosition(list, cursor);
    } catch (InvalidCursorException rejection) {
      throw rejection;
    } catch (RuntimeException _) {
      throw new InvalidCursorException("Cursor is not valid.");
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

  private Position readPosition(ListIdentity list, String cursor) {
    PositionCursorPayload payload = payloadReader.readValue(Base64.getUrlDecoder().decode(cursor));
    if (!issuedFor(list, payload)) {
      throw new InvalidCursorException("Cursor belongs to another list.");
    }

    return payload.position();
  }

  private boolean issuedFor(ListIdentity list, PositionCursorPayload payload) {
    return payload.version() == VERSION
        && payload.list().equals(list.list())
        && payload.scope().equals(fingerprint(list));
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
