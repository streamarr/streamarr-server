package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.auth.ChangedManagerInvitation;
import com.streamarr.server.domain.auth.ManagerInvitationAction;
import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeProfileManagerInvitationRepository;
import com.streamarr.server.fakes.FakeTransactionManager;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.services.events.auth.ProfileManagerInvitationChangedEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("UnitTest")
@DisplayName("Manager Invitation Expiry Sweeper Tests")
class ManagerInvitationExpirySweeperTest {

  private final AtomicReference<Instant> currentTime =
      new AtomicReference<>(Instant.parse("2026-11-11T12:00:00Z"));
  private final FakeProfileManagerInvitationRepository invitations =
      new FakeProfileManagerInvitationRepository();
  private final CapturingEventPublisher events = new CapturingEventPublisher();
  private final ManagerInvitationExpirySweeper sweeper =
      new ManagerInvitationExpirySweeper(
          new ProfileManagerInvitationLifecycle(invitations, events),
          new TransactionTemplate(new FakeTransactionManager()),
          new MutableClock(currentTime));

  @Test
  @DisplayName("Should expire and announce an invitation when its time runs out before the sweep")
  void shouldExpireAndAnnounceAnInvitationWhenItsTimeRunsOutBeforeTheSweep() {
    var lapsing = invitation(Duration.ofMinutes(1), "mara@example.com");
    var live = invitation(Duration.ofMinutes(10), "cam@example.com");
    currentTime.set(currentTime.get().plus(Duration.ofMinutes(2)));

    sweeper.sweep();

    assertThat(invitations.findById(lapsing.getId()))
        .hasValueSatisfying(
            invitation -> {
              assertThat(invitation.getStatus()).isEqualTo(ProfileManagerInvitationStatus.EXPIRED);
              assertThat(invitation.getDecidedAt()).isEqualTo(currentTime.get());
            });
    assertThat(invitations.findById(live.getId()))
        .hasValueSatisfying(
            invitation ->
                assertThat(invitation.getStatus())
                    .isEqualTo(ProfileManagerInvitationStatus.PENDING));
    assertThat(events.getEventsOfType(ProfileManagerInvitationChangedEvent.class))
        .containsExactly(
            new ProfileManagerInvitationChangedEvent(
                ChangedManagerInvitation.of(lapsing),
                new ManagerInvitationAction.StoppedWaiting()));
  }

  @Test
  @DisplayName("Should announce nothing when no invitation's time has run out")
  void shouldAnnounceNothingWhenNoInvitationsTimeHasRunOut() {
    invitation(Duration.ofMinutes(10), "mara@example.com");

    sweeper.sweep();

    assertThat(events.getEventsOfType(ProfileManagerInvitationChangedEvent.class)).isEmpty();
  }

  private ProfileManagerInvitation invitation(Duration untilExpiry, String address) {
    return invitations.save(
        ProfileManagerInvitation.builder()
            .profileId(UUID.randomUUID())
            .profileName("Alex")
            .inviterAccountId(UUID.randomUUID())
            .inviterDisplayName("Sam")
            .recipientEmail(address)
            .expiresAt(currentTime.get().plus(untilExpiry))
            .publicId(UUID.randomUUID().toString())
            .secretDigest(new byte[] {1})
            .build());
  }
}
