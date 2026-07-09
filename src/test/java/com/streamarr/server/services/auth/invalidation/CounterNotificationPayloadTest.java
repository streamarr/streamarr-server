package com.streamarr.server.services.auth.invalidation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.streamarr.server.services.auth.CounterKind;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Counter Notification Payload Tests")
class CounterNotificationPayloadTest {

  @Test
  @DisplayName("Should parse membership payload keeping inner colon")
  void shouldParseMembershipPayloadKeepingInnerColon() {
    var accountId = UUID.randomUUID();
    var householdId = UUID.randomUUID();

    var parsed =
        CounterNotificationPayload.parse("MEMBERSHIP|" + accountId + ":" + householdId + "|7");

    assertThat(parsed)
        .contains(
            new CounterNotificationPayload(
                CounterKind.MEMBERSHIP, accountId + ":" + householdId, 7L));
  }

  @Test
  @DisplayName("Should parse session payload")
  void shouldParseSessionPayload() {
    var sessionId = UUID.randomUUID();

    var parsed = CounterNotificationPayload.parse("SESSION|" + sessionId + "|3");

    assertThat(parsed)
        .contains(new CounterNotificationPayload(CounterKind.SESSION, sessionId.toString(), 3L));
  }

  @ParameterizedTest(name = "Should round-trip {0} through encode and parse")
  @EnumSource(CounterKind.class)
  @DisplayName("Should round-trip every counter kind through encode and parse")
  void shouldRoundTripEveryCounterKindThroughEncodeAndParse(CounterKind kind) {
    var payload = new CounterNotificationPayload(kind, "left:right", 7L);

    assertThat(CounterNotificationPayload.parse(payload.encode())).contains(payload);
  }

  @ParameterizedTest(name = "Should reject malformed payload [{0}]")
  @MethodSource("malformedPayloads")
  void shouldRejectMalformedPayloads(String payload) {
    assertThat(CounterNotificationPayload.parse(payload)).isEmpty();
  }

  private static Stream<String> malformedPayloads() {
    return Stream.<String>of(
        null,
        "",
        "SESSION|missing-version",
        "NOT_A_KIND|key|1",
        "SESSION|key|not-a-number",
        "SESSION|key|",
        "SESSION||1",
        "SESSION|key|1|extra",
        "SESSION|key|-1");
  }

  @Test
  @DisplayName("Should reject construction when kind is missing")
  void shouldRejectConstructionWhenKindIsMissing() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new CounterNotificationPayload(null, "key", 1L));
  }

  @Test
  @DisplayName("Should reject construction when key is blank")
  void shouldRejectConstructionWhenKeyIsBlank() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new CounterNotificationPayload(CounterKind.SESSION, " ", 1L));
  }

  @Test
  @DisplayName("Should reject construction when key contains the delimiter")
  void shouldRejectConstructionWhenKeyContainsTheDelimiter() {
    // A piped key would encode to a payload every listener silently drops.
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new CounterNotificationPayload(CounterKind.SESSION, "left|right", 1L));
  }

  @Test
  @DisplayName("Should reject construction when version is negative")
  void shouldRejectConstructionWhenVersionIsNegative() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new CounterNotificationPayload(CounterKind.SESSION, "key", -1L));
  }
}
