package com.streamarr.server.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@Tag("UnitTest")
@DisplayName("Share Status Filter Tests")
class ShareStatusFilterTest {

  private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

  @ParameterizedTest(name = "{0}")
  @EnumSource(ProfileShareStatus.class)
  @DisplayName("Should keep every share when the filter names no status")
  void shouldKeepEveryShareWhenFilterNamesNoStatus(ProfileShareStatus status) {
    var filter = new ShareStatusFilter(Set.of(), NOW);

    assertThat(filter.keeps(share(status, NOW.plusSeconds(60)))).isTrue();
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(ProfileShareStatus.class)
  @DisplayName("Should keep only the named status when the filter names one")
  void shouldKeepOnlyNamedStatusWhenFilterNamesOne(ProfileShareStatus status) {
    var filter = new ShareStatusFilter(Set.of(status), NOW);

    for (var other : EnumSet.complementOf(EnumSet.of(status))) {
      assertThat(filter.keeps(share(other, null))).as("stored %s", other).isFalse();
    }

    assertThat(filter.keeps(share(status, null))).isTrue();
  }

  @Test
  @DisplayName("Should read an offer past its expiry as expired when the filter keeps statuses")
  void shouldReadOfferPastItsExpiryAsExpiredWhenFilterKeepsStatuses() {
    var stale = share(ProfileShareStatus.PENDING, NOW);

    assertThat(new ShareStatusFilter(Set.of(ProfileShareStatus.EXPIRED), NOW).keeps(stale))
        .isTrue();
    assertThat(new ShareStatusFilter(Set.of(ProfileShareStatus.PENDING), NOW).keeps(stale))
        .isFalse();
  }

  @Test
  @DisplayName("Should keep an unexpired offer as pending when the filter keeps pending offers")
  void shouldKeepUnexpiredOfferAsPendingWhenFilterKeepsPendingOffers() {
    var live = share(ProfileShareStatus.PENDING, NOW.plusSeconds(1));

    assertThat(new ShareStatusFilter(Set.of(ProfileShareStatus.PENDING), NOW).keeps(live)).isTrue();
    assertThat(new ShareStatusFilter(Set.of(ProfileShareStatus.EXPIRED), NOW).keeps(live))
        .isFalse();
  }

  private static ProfileHouseholdShare share(ProfileShareStatus status, Instant expiresAt) {
    return ProfileHouseholdShare.builder().status(status).expiresAt(expiresAt).build();
  }
}
