package com.streamarr.server.graphql.cursor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.streamarr.server.services.pagination.ListIdentity;
import com.streamarr.server.services.pagination.Position;
import com.streamarr.server.services.pagination.SortValue;
import com.streamarr.server.support.MalformedCursors;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Tag("UnitTest")
@DisplayName("Position cursor codec")
class PositionCursorCodecTest {

  private static final UUID PROFILE = UUID.fromString("5d2f6a8e-0c1b-4f3e-9a7d-2b4c6e8f0a1c");
  private static final ListIdentity CONTINUE_WATCHING =
      new ListIdentity("continue-watching", PROFILE);

  private final PositionCursorCodec codec = new PositionCursorCodec(new ObjectMapper());

  static Stream<SortValue> sortValues() {
    return Stream.of(
        new SortValue.OfText("Élite"),
        new SortValue.OfInstant(Instant.parse("1969-12-31T23:59:59.250Z")),
        new SortValue.OfInstant(Instant.parse("+294276-12-31T23:59:59.999999Z")),
        new SortValue.OfDate(LocalDate.of(2011, 12, 30)),
        new SortValue.OfNumber(90),
        new SortValue.Absent());
  }

  @ParameterizedTest
  @MethodSource("sortValues")
  @DisplayName(
      "Should decode the position a cursor was encoded with when it continues its own list")
  void shouldDecodeThePositionACursorWasEncodedWithWhenItContinuesItsOwnList(SortValue value) {
    var position = new Position(value, UUID.randomUUID());

    var cursor = codec.encode(CONTINUE_WATCHING, position).getValue();

    assertThat(codec.decode(CONTINUE_WATCHING, cursor)).isEqualTo(position);
  }

  @Test
  @DisplayName("Should reject a cursor when it continues another scope of the same list")
  void shouldRejectACursorWhenItContinuesAnotherScopeOfTheSameList() {
    var cursor =
        codec
            .encode(
                new ListIdentity("continue-watching", UUID.randomUUID()),
                new Position(new SortValue.OfNumber(1), UUID.randomUUID()))
            .getValue();

    assertThatThrownBy(() -> codec.decode(CONTINUE_WATCHING, cursor))
        .isInstanceOf(InvalidCursorException.class);
  }

  @Test
  @DisplayName("Should reject a cursor when it continues another list")
  void shouldRejectACursorWhenItContinuesAnotherList() {
    var cursor =
        codec
            .encode(
                new ListIdentity("household-devices", PROFILE),
                new Position(new SortValue.OfNumber(1), UUID.randomUUID()))
            .getValue();

    assertThatThrownBy(() -> codec.decode(CONTINUE_WATCHING, cursor))
        .isInstanceOf(InvalidCursorException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"not base64 !", "cGxhaW4gdGV4dA", "e30"})
  @DisplayName("Should reject a cursor when it is not a position cursor")
  void shouldRejectACursorWhenItIsNotAPositionCursor(String cursor) {
    assertThatThrownBy(() -> codec.decode(CONTINUE_WATCHING, cursor))
        .isInstanceOf(InvalidCursorException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "-item",
        "item=not-a-uuid",
        "-type",
        "type=COLOUR",
        "type=INSTANT,value=yesterday",
        "type=INSTANT,value=1582-12-31T23:59:59Z",
        "type=INSTANT,value=+294277-01-01T00:00:00Z",
        "type=DATE,value=+5874898-01-01",
        "type=DATE,value=1582-10-15",
        "type=INSTANT,value=2026-10-20T11:59:59.999999999Z",
        "value=ninety",
        "type=TEXT,-value",
        "version=2",
        "sortedBy=title"
      })
  @DisplayName("Should reject a cursor when one part of its position cannot be read")
  void shouldRejectACursorWhenOnePartOfItsPositionCannotBeRead(String corruption) {
    var genuine =
        codec
            .encode(CONTINUE_WATCHING, new Position(new SortValue.OfNumber(1), UUID.randomUUID()))
            .getValue();
    assertThat(codec.decode(CONTINUE_WATCHING, genuine)).isNotNull();

    var cursor = corrupt(genuine, corruption);

    assertThatThrownBy(() -> codec.decode(CONTINUE_WATCHING, cursor))
        .isInstanceOf(InvalidCursorException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"version\":{}}",
        "{\"version\":[1]}",
        "{\"version\":true}",
        "{\"version\":1.5}",
        "{\"version\":99999999999999999999}",
        "{\"version\":1,\"list\":{}}",
        "{\"version\":1,\"list\":\"continue-watching\",\"scope\":[]}"
      })
  @DisplayName("Should reject a cursor when its envelope fields have the wrong shape")
  void shouldRejectACursorWhenItsEnvelopeFieldsHaveTheWrongShape(String payload) {
    var cursor =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(payload.getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> codec.decode(CONTINUE_WATCHING, cursor))
        .isInstanceOf(InvalidCursorException.class);
  }

  static Stream<String> malformedCursors() {
    var genuine =
        new PositionCursorCodec(new ObjectMapper())
            .encode(
                CONTINUE_WATCHING,
                new Position(
                    new SortValue.OfInstant(Instant.parse("2026-10-20T12:00:00Z")),
                    UUID.randomUUID()))
            .getValue();
    return MalformedCursors.of(genuine);
  }

  @ParameterizedTest
  @MethodSource("malformedCursors")
  @DisplayName("Should decode a cursor or reject it as invalid when a client alters it")
  void shouldDecodeACursorOrRejectItAsInvalidWhenAClientAltersIt(String cursor) {
    var thrown = catchThrowable(() -> codec.decode(CONTINUE_WATCHING, cursor));

    assertThat(thrown)
        .satisfiesAnyOf(
            rejection -> assertThat(rejection).isNull(),
            rejection -> assertThat(rejection).isInstanceOf(InvalidCursorException.class));
  }

  private static String corrupt(String cursor, String corruption) {
    var json = new ObjectMapper();
    var node =
        (ObjectNode)
            json.readTree(
                new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
    for (var change : corruption.split(",")) {
      if (change.startsWith("-")) {
        node.remove(change.substring(1));
        continue;
      }

      var parts = change.split("=", 2);
      node.put(parts[0], parts[1]);
    }

    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(json.writeValueAsString(node).getBytes(StandardCharsets.UTF_8));
  }
}
