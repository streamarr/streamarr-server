package com.streamarr.server.services.auth.invalidation;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.services.auth.CounterKind;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

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

  @Test
  @DisplayName("Should reject malformed payloads")
  void shouldRejectMalformedPayloads() {
    assertThat(CounterNotificationPayload.parse(null)).isEmpty();
    assertThat(CounterNotificationPayload.parse("")).isEmpty();
    assertThat(CounterNotificationPayload.parse("SESSION|missing-version")).isEmpty();
    assertThat(CounterNotificationPayload.parse("NOT_A_KIND|key|1")).isEmpty();
    assertThat(CounterNotificationPayload.parse("SESSION|key|not-a-number")).isEmpty();
  }
}
