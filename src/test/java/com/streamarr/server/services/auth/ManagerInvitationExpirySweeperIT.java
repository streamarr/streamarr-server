package com.streamarr.server.services.auth;

import static com.streamarr.server.jooq.generated.tables.ProfileManagerInvitation.PROFILE_MANAGER_INVITATION;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.ManagerInvitationAction;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import com.streamarr.server.domain.auth.SystemActor;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.repositories.auth.ProfileManagerInvitationRepository;
import com.streamarr.server.services.events.auth.ProfileManagerInvitationChangedEvent;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupport.TestIdentity;
import com.streamarr.server.support.ProfileCastSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

@Tag("IntegrationTest")
@DisplayName("Manager Invitation Expiry Sweeper Integration Tests")
@RecordApplicationEvents
class ManagerInvitationExpirySweeperIT extends AbstractIntegrationTest {

  @Autowired private ManagerInvitationExpirySweeper sweeper;
  @Autowired private ProfileManagerInvitationRepository invitationRepository;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private ProfileCastSupport cast;
  @Autowired private DSLContext dsl;
  @Autowired private ApplicationEvents events;

  private TestIdentity sam;
  private TestIdentity mara;
  private Profile alex;

  @BeforeEach
  void setUp() {
    // The sweep expires every lapsed invitation in the database, not only this test's.
    invitationRepository.deleteAll();
    sam = authTestSupport.createIdentity();
    mara = authTestSupport.createIdentity();
    alex = cast.profileManagedBy(sam, ProfileFixture.defaultProfileBuilder().name("Alex"));
  }

  @AfterEach
  void tearDown() {
    invitationRepository.deleteAll();
    authTestSupport.deleteIdentity(mara);
    authTestSupport.deleteIdentity(sam);
  }

  @Test
  @DisplayName("Should store expired and announce it when an invitation's time runs out")
  void shouldStoreExpiredAndAnnounceItWhenAnInvitationsTimeRunsOut() {
    var lapsed = invitation(Optional.of(mara), "mara@example.com", Duration.ofMinutes(-1));
    var live = invitation(Optional.empty(), "cam@example.com", Duration.ofDays(1));

    sweeper.sweep();

    assertThat(statusOf(lapsed)).isEqualTo(ProfileManagerInvitationStatus.EXPIRED);
    assertThat(statusOf(live)).isEqualTo(ProfileManagerInvitationStatus.PENDING);
    assertThat(lastModifierOf(lapsed)).isEqualTo(SystemActor.ID);
    assertThat(events.stream(ProfileManagerInvitationChangedEvent.class))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.invitation().invitationId()).isEqualTo(lapsed.getId());
              assertThat(event.invitation().recipientAccountId()).contains(mara.account().getId());
              assertThat(event.action()).isEqualTo(new ManagerInvitationAction.StoppedWaiting());
            });
  }

  @Test
  @DisplayName("Should expire it as any other when nobody can answer the invitation")
  void shouldExpireItAsAnyOtherWhenNobodyCanAnswerTheInvitation() {
    var unanswerable = invitation(Optional.empty(), "nobody@example.com", Duration.ofMinutes(-1));

    sweeper.sweep();

    assertThat(statusOf(unanswerable)).isEqualTo(ProfileManagerInvitationStatus.EXPIRED);
    assertThat(events.stream(ProfileManagerInvitationChangedEvent.class))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.invitation().invitationId()).isEqualTo(unanswerable.getId());
              assertThat(event.invitation().recipientAccountId()).isEmpty();
            });
  }

  private ProfileManagerInvitation invitation(
      Optional<TestIdentity> named, String address, Duration untilExpiry) {
    return invitationRepository.saveAndFlush(
        ProfileManagerInvitation.builder()
            .profileId(alex.getId())
            .profileName(alex.getName())
            .inviterAccountId(sam.account().getId())
            .inviterDisplayName(sam.account().getDisplayName())
            .recipientAccountId(named.map(identity -> identity.account().getId()).orElse(null))
            .recipientEmail(address)
            .expiresAt(Instant.now().plus(untilExpiry))
            .publicId(UUID.randomUUID().toString())
            .secretDigest(new byte[] {1})
            .build());
  }

  private ProfileManagerInvitationStatus statusOf(ProfileManagerInvitation invitation) {
    return invitationRepository.findById(invitation.getId()).orElseThrow().getStatus();
  }

  private UUID lastModifierOf(ProfileManagerInvitation invitation) {
    return dsl.select(PROFILE_MANAGER_INVITATION.LAST_MODIFIED_BY)
        .from(PROFILE_MANAGER_INVITATION)
        .where(PROFILE_MANAGER_INVITATION.ID.eq(invitation.getId()))
        .fetchSingle(PROFILE_MANAGER_INVITATION.LAST_MODIFIED_BY);
  }
}
