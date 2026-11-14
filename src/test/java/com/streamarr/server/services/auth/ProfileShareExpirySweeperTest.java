package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.auth.ChangedShare;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeProfileHouseholdShareRepository;
import com.streamarr.server.fakes.FakeTransactionManager;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.services.events.auth.ProfileShareChangedEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("UnitTest")
@DisplayName("Profile Share Expiry Sweeper Tests")
class ProfileShareExpirySweeperTest {

  private final AtomicReference<Instant> currentTime =
      new AtomicReference<>(Instant.parse("2026-08-21T12:00:00Z"));
  private final FakeProfileHouseholdShareRepository shares =
      new FakeProfileHouseholdShareRepository();
  private final CapturingEventPublisher events = new CapturingEventPublisher();
  private final ProfileShareExpirySweeper sweeper =
      new ProfileShareExpirySweeper(
          new ProfileShareLifecycle(shares, events),
          new TransactionTemplate(new FakeTransactionManager()),
          new MutableClock(currentTime));

  @Test
  @DisplayName("Should expire and announce an offer when its time runs out before the sweep")
  void shouldExpireAndAnnounceAnOfferWhenItsTimeRunsOutBeforeTheSweep() {
    var lapsing = offer(Duration.ofMinutes(1));
    var live = offer(Duration.ofMinutes(10));
    currentTime.set(currentTime.get().plus(Duration.ofMinutes(2)));

    sweeper.sweep();

    assertThat(shares.findById(lapsing.getId()))
        .hasValueSatisfying(
            share -> {
              assertThat(share.getStatus()).isEqualTo(ProfileShareStatus.EXPIRED);
              assertThat(share.getDecidedAt()).isEqualTo(currentTime.get());
            });
    assertThat(shares.findById(live.getId()))
        .hasValueSatisfying(
            share -> assertThat(share.getStatus()).isEqualTo(ProfileShareStatus.PENDING));
    assertThat(events.getEventsOfType(ProfileShareChangedEvent.class))
        .extracting(ProfileShareChangedEvent::share)
        .containsExactly(ChangedShare.of(lapsing));
  }

  @Test
  @DisplayName("Should announce nothing when no offer's time has run out")
  void shouldAnnounceNothingWhenNoOffersTimeHasRunOut() {
    offer(Duration.ofMinutes(10));

    sweeper.sweep();

    assertThat(events.getEventsOfType(ProfileShareChangedEvent.class)).isEmpty();
  }

  private ProfileHouseholdShare offer(Duration untilExpiry) {
    return shares.save(
        ProfileHouseholdShare.builder()
            .profileId(UUID.randomUUID())
            .householdId(UUID.randomUUID())
            .status(ProfileShareStatus.PENDING)
            .offeredByAccountId(UUID.randomUUID())
            .expiresAt(currentTime.get().plus(untilExpiry))
            .build());
  }
}
