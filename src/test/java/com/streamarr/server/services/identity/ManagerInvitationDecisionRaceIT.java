package com.streamarr.server.services.identity;

import static com.streamarr.server.support.PostgresLockTestSupport.lockRow;
import static com.streamarr.server.support.PostgresLockTestSupport.waitersBehind;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.repositories.auth.ProfileManagerInvitationRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.ManagerInvitationExpirySweeper;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupport.TestIdentity;
import com.streamarr.server.support.ControlledClockConfiguration;
import com.streamarr.server.support.PostgresLockTestSupport.RowLockTarget;
import com.streamarr.server.support.ProfileCastSupport;
import com.streamarr.server.support.TestFixtureWork;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Of two writes that meet at one invitation to manage a Profile, exactly one takes effect (ADR 0024
 * §Invitations). Each race is held at one row: both writes wait behind it and are released
 * together.
 */
@Tag("IntegrationTest")
@DisplayName("Manager Invitation Decision Race Integration Tests")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(ControlledClockConfiguration.class)
class ManagerInvitationDecisionRaceIT extends AbstractIntegrationTest {

  @Autowired private MutableClock clock;
  @Autowired private ProfileManagerAdministrationService managerService;
  @Autowired private ProfileAdministrationService profileService;
  @Autowired private ManagerInvitationExpirySweeper expirySweeper;
  @Autowired private ProfileManagerInvitationRepository invitationRepository;
  @Autowired private ProfileManagerRepository profileManagerRepository;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private ProfileCastSupport cast;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbcTemplate;

  private TestIdentity sam;
  private TestIdentity mara;
  private Profile alex;

  @BeforeEach
  void setUp() {
    clock.advance(Duration.between(clock.instant(), Instant.now()));
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
  @DisplayName("Should let exactly one of accept and cancel win when both arrive at once")
  void shouldLetExactlyOneOfAcceptAndCancelWinWhenBothArriveAtOnce() throws Exception {
    var invitationId = invite(mara.account().getEmail()).getId();

    var outcomes =
        heldAt(
            row("profile_manager_invitation", invitationId),
            "%profile_manager_invitation%",
            () -> managerService.acceptManagerInvitation(identity(mara), invitationId),
            () -> managerService.cancelManagerInvitation(identity(sam), invitationId));

    assertThat(outcomes).filteredOn(Outcome.Accepted.class::isInstance).hasSize(1);
    assertThat(outcomes)
        .filteredOn(Outcome.Rejected.class::isInstance)
        .containsExactly(Outcome.rejected(new ManagerRejections.InvitationNotPending()));
    var accepted = outcomes.getFirst() instanceof Outcome.Accepted<?, ?>;
    assertThat(statusOf(invitationId))
        .isEqualTo(
            accepted
                ? ProfileManagerInvitationStatus.ACCEPTED
                : ProfileManagerInvitationStatus.CANCELED);
    assertThat(maraManagesAlex()).isEqualTo(accepted);
  }

  @Test
  @DisplayName("Should grant management once when the named Account accepts twice at once")
  void shouldGrantManagementOnceWhenTheNamedAccountAcceptsTwiceAtOnce() throws Exception {
    var invitationId = invite(mara.account().getEmail()).getId();

    var outcomes =
        heldAt(
            row("profile_manager_invitation", invitationId),
            "%",
            () -> managerService.acceptManagerInvitation(identity(mara), invitationId),
            () -> managerService.acceptManagerInvitation(identity(mara), invitationId));

    assertThat(outcomes).filteredOn(Outcome.Accepted.class::isInstance).hasSize(1);
    assertThat(outcomes)
        .filteredOn(Outcome.Rejected.class::isInstance)
        .containsExactly(Outcome.rejected(new ManagerRejections.InvitationNotPending()));
    assertThat(statusOf(invitationId)).isEqualTo(ProfileManagerInvitationStatus.ACCEPTED);
    assertThat(maraManagesAlex()).isTrue();
  }

  @Test
  @DisplayName("Should grant no management when the sweep stores the expiry first")
  void shouldGrantNoManagementWhenTheSweepStoresTheExpiryFirst() throws Exception {
    var invitation = invite(mara.account().getEmail());
    var invitationId = invitation.getId();

    // The acceptance starts while the invitation is live and waits at the Profile's row, which the
    // sweep never takes; the clock passes the expiry and the sweep stores it while it waits.
    Outcome<ProfileManagerInvitation, ManagerRejections.Accept> outcome;
    try (var executor = TestFixtureWork.newExecutorRunningAsTestFixtureActor();
        var profileLock = lockRow(row("profile", alex.getId()))) {
      var acceptance =
          executor.submit(
              () -> managerService.acceptManagerInvitation(identity(mara), invitationId));
      await()
          .atMost(Duration.ofSeconds(10))
          .until(() -> waitersBehind(jdbcTemplate, profileLock.backendPid(), "%profile%") == 1);
      clock.advance(Duration.between(clock.instant(), invitation.getExpiresAt().plusSeconds(1)));
      expirySweeper.sweep();
      assertThat(statusOf(invitationId)).isEqualTo(ProfileManagerInvitationStatus.EXPIRED);

      profileLock.release();
      outcome = acceptance.get(10, TimeUnit.SECONDS);
    }

    assertThat(outcome).isEqualTo(Outcome.rejected(new ManagerRejections.InvitationNotPending()));
    assertThat(maraManagesAlex()).isFalse();
  }

  @Test
  @DisplayName(
      "Should keep one pending invitation when two invites to the same address arrive at once")
  void shouldKeepOnePendingInvitationWhenTwoInvitesToTheSameAddressArriveAtOnce() throws Exception {
    var outcomes =
        heldAt(
            row("profile", alex.getId()),
            "%profile%",
            () ->
                managerService.inviteProfileManager(
                    identity(sam), alex.getId(), "mara@example.com"),
            () ->
                managerService.inviteProfileManager(
                    identity(sam), alex.getId(), "Mara@Example.com"));

    assertThat(outcomes).hasSize(2).allMatch(Outcome.Accepted.class::isInstance);
    assertThat(invitationRepository.findAll())
        .filteredOn(invitation -> alex.getId().equals(invitation.getProfileId()))
        .extracting(ProfileManagerInvitation::getStatus)
        .containsExactlyInAnyOrder(
            ProfileManagerInvitationStatus.PENDING, ProfileManagerInvitationStatus.INVALIDATED);
  }

  @Test
  @DisplayName("Should refuse deleting the Profile when an invitation to manage it is issued first")
  void shouldRefuseDeletingTheProfileWhenAnInvitationToManageItIsIssuedFirst() throws Exception {
    var kit =
        cast.unavailableProfileManagedBy(sam, ProfileFixture.defaultProfileBuilder().name("Kit"));
    var inviter = identity(sam);
    var deleter = authTestSupport.freshIdentityOf(sam);

    // The invitation waits at the Profile's row first and the deletion behind it, so PostgreSQL
    // gives the row to the invitation first; the deletion must then find it waiting.
    Outcome<?, ?> invited;
    Outcome<?, ?> deleted;
    try (var executor = TestFixtureWork.newExecutorRunningAsTestFixtureActor();
        var profileLock = lockRow(row("profile", kit.getId()))) {
      var invitation =
          executor.submit(
              () ->
                  managerService.inviteProfileManager(
                      inviter, kit.getId(), mara.account().getEmail()));
      await()
          .atMost(Duration.ofSeconds(10))
          .until(() -> waitersBehind(jdbcTemplate, profileLock.backendPid(), "%profile%") == 1);
      var deletion = executor.submit(() -> profileService.deleteProfile(deleter, kit.getId()));
      await()
          .atMost(Duration.ofSeconds(10))
          .until(() -> waitersBehind(jdbcTemplate, profileLock.backendPid(), "%profile%") == 2);

      profileLock.release();
      invited = invitation.get(10, TimeUnit.SECONDS);
      deleted = deletion.get(10, TimeUnit.SECONDS);
    }

    assertThat(invited).isInstanceOf(Outcome.Accepted.class);
    assertThat(deleted).isEqualTo(Outcome.rejected(new ProfileRejections.ProfileNotDeletable()));
    assertThat(profileRepository.existsById(kit.getId())).isTrue();
    assertThat(invitationRepository.findAll())
        .filteredOn(invitation -> kit.getId().equals(invitation.getProfileId()))
        .extracting(ProfileManagerInvitation::getStatus)
        .containsExactly(ProfileManagerInvitationStatus.PENDING);
  }

  /** Runs both writes while the row is held, waits until both wait behind it, then releases it. */
  private List<Outcome<?, ?>> heldAt(
      RowLockTarget target,
      String waitingStatement,
      Callable<Outcome<?, ?>> first,
      Callable<Outcome<?, ?>> second)
      throws Exception {
    try (var executor = TestFixtureWork.newExecutorRunningAsTestFixtureActor();
        var lock = lockRow(target)) {
      var firstOutcome = executor.submit(first);
      var secondOutcome = executor.submit(second);
      await()
          .atMost(Duration.ofSeconds(10))
          .until(() -> waitersBehind(jdbcTemplate, lock.backendPid(), waitingStatement) == 2);

      lock.release();
      return List.of(
          firstOutcome.get(10, TimeUnit.SECONDS), secondOutcome.get(10, TimeUnit.SECONDS));
    }
  }

  private ProfileManagerInvitation invite(String address) {
    return managerService
        .inviteProfileManager(identity(sam), alex.getId(), address)
        .fold(
            ProfileManagerAdministrationService.IssuedManagerInvitation::invitation,
            rejections -> {
              throw new AssertionError("expected an invitation but got " + rejections);
            });
  }

  private RowLockTarget row(String table, UUID id) {
    return RowLockTarget.builder().dataSource(dataSource).table(table).rowId(id).build();
  }

  private AuthenticatedIdentity identity(TestIdentity person) {
    return authTestSupport.identityOf(person);
  }

  private ProfileManagerInvitationStatus statusOf(UUID invitationId) {
    return invitationRepository.findById(invitationId).orElseThrow().getStatus();
  }

  private boolean maraManagesAlex() {
    return profileManagerRepository.existsByAccountIdAndProfileId(
        mara.account().getId(), alex.getId());
  }
}
