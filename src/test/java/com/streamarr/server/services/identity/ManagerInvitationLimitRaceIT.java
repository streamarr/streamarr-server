package com.streamarr.server.services.identity;

import static com.streamarr.server.support.PostgresLockTestSupport.lockRow;
import static com.streamarr.server.support.PostgresLockTestSupport.waitersBehind;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.repositories.auth.ProfileManagerInvitationRepository;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.PostgresLockTestSupport.RowLockTarget;
import com.streamarr.server.support.ProfileCastSupport;
import com.streamarr.server.support.TestFixtureWork;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * At most ten invitations to manage one Profile wait at once, so of two invitations that find room
 * for one more, exactly one is made (ADR 0024 §Invitations). The race is held at the Profile's row:
 * both invitations wait behind it and are released together.
 */
@Tag("IntegrationTest")
@DisplayName("Manager Invitation Limit Race Integration Tests")
class ManagerInvitationLimitRaceIT extends AbstractIntegrationTest {

  private static final String WAITING_ON_PROFILE = "%profile%";

  @Autowired private ProfileManagerAdministrationService managerService;
  @Autowired private ProfileManagerInvitationRepository invitationRepository;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private ProfileCastSupport cast;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbcTemplate;

  private AuthTestSupport.TestIdentity sam;
  private Profile alex;

  @BeforeEach
  void setUp() {
    sam = authTestSupport.createIdentity();
    alex = cast.profileManagedBy(sam, ProfileFixture.defaultProfileBuilder().name("Alex"));
  }

  @AfterEach
  void tearDown() {
    invitationRepository.deleteAll();
    authTestSupport.deleteIdentity(sam);
  }

  @Test
  @DisplayName(
      "Should issue exactly one invitation when two invites find room for one at the same time")
  void shouldIssueExactlyOneInvitationWhenTwoInvitesFindRoomForOneAtTheSameTime() throws Exception {
    for (var person = 0; person < 9; person++) {
      assertThat(invite("person.%d@example.com".formatted(person)))
          .isInstanceOf(Outcome.Accepted.class);
    }

    var profileRow =
        RowLockTarget.builder().dataSource(dataSource).table("profile").rowId(alex.getId()).build();
    List<Outcome<ProfileManagerAdministrationService.IssuedManagerInvitation, ?>> outcomes;
    try (var executor = TestFixtureWork.newExecutorRunningAsTestFixtureActor();
        var profileLock = lockRow(profileRow)) {
      var first = executor.submit(() -> invite("mara@example.com"));
      var second = executor.submit(() -> invite("cabin.guest@example.com"));
      await()
          .atMost(Duration.ofSeconds(10))
          .until(
              () -> waitersBehind(jdbcTemplate, profileLock.backendPid(), WAITING_ON_PROFILE) == 2);

      profileLock.release();
      outcomes = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
    }

    assertThat(outcomes).filteredOn(Outcome.Accepted.class::isInstance).hasSize(1);
    assertThat(outcomes)
        .filteredOn(Outcome.Rejected.class::isInstance)
        .containsExactly(Outcome.rejected(new ManagerRejections.ManagerInvitationLimitReached()));
    assertThat(invitationRepository.findAll())
        .filteredOn(invitation -> alex.getId().equals(invitation.getProfileId()))
        .filteredOn(
            invitation ->
                invitation.statusAt(Instant.now()) == ProfileManagerInvitationStatus.PENDING)
        .hasSize(10);
  }

  private Outcome<ProfileManagerAdministrationService.IssuedManagerInvitation, ?> invite(
      String email) {
    return managerService.inviteProfileManager(
        authTestSupport.identityOf(sam), alex.getId(), email);
  }
}
