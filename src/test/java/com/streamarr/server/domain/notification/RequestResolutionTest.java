package com.streamarr.server.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Request Resolution Tests")
class RequestResolutionTest {

  private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");

  @ParameterizedTest(name = "{0}")
  @MethodSource("statuses")
  @DisplayName("Should read the resolution from the offer when the offer stops waiting")
  void shouldReadTheResolutionFromTheOfferWhenTheOfferStopsWaiting(
      ProfileShareStatus status, Optional<RequestResolution> resolution) {
    var offer = offer(status, NOW.plusSeconds(60));

    assertThat(RequestResolution.of(offer, NOW)).isEqualTo(resolution);
  }

  static Stream<Arguments> statuses() {
    return Stream.of(
        Arguments.of(ProfileShareStatus.PENDING, Optional.empty()),
        Arguments.of(ProfileShareStatus.ACTIVE, Optional.of(RequestResolution.DECIDED)),
        Arguments.of(ProfileShareStatus.ENDED, Optional.of(RequestResolution.DECIDED)),
        Arguments.of(ProfileShareStatus.REJECTED, Optional.of(RequestResolution.DECIDED)),
        Arguments.of(ProfileShareStatus.CANCELED, Optional.of(RequestResolution.WITHDRAWN)),
        Arguments.of(ProfileShareStatus.INVALIDATED, Optional.of(RequestResolution.WITHDRAWN)),
        Arguments.of(ProfileShareStatus.EXPIRED, Optional.of(RequestResolution.EXPIRED)));
  }

  @Test
  @DisplayName("Should read as expired when the offer's time ran out before the sweep stored it")
  void shouldReadAsExpiredWhenTheOffersTimeRanOutBeforeTheSweepStoredIt() {
    var lapsed = offer(ProfileShareStatus.PENDING, NOW);

    assertThat(RequestResolution.of(lapsed, NOW)).contains(RequestResolution.EXPIRED);
  }

  private static ProfileHouseholdShare offer(ProfileShareStatus status, Instant expiresAt) {
    return ProfileHouseholdShare.builder().status(status).expiresAt(expiresAt).build();
  }
}
