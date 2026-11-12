package com.streamarr.server.services.identity;

import static com.streamarr.server.support.PostgresLockTestSupport.awaitWaitersBehind;
import static com.streamarr.server.support.PostgresLockTestSupport.lockRow;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.notification.RecipientReference;
import com.streamarr.server.domain.notification.SubjectReference;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupport.TestIdentity;
import com.streamarr.server.support.PostgresLockTestSupport.RowLockTarget;
import com.streamarr.server.support.StoredNotificationRows;
import com.streamarr.server.support.TestFixtureWork;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ending a visit holds the visitor's Personal Profile and then reads the visitor's Account, which
 * manages that Profile, to tell it the visit is over. Deleting the visitor's Account removes both.
 * So the two meet on the same two rows, and they must take them in the same order.
 */
@Tag("IntegrationTest")
@DisplayName("Visit End Account Deletion Race Integration Tests")
class VisitEndAccountDeletionRaceIT extends AbstractIntegrationTest {

  @Autowired private ProfileSharingService sharing;
  @Autowired private AccountLifecycleService accountLifecycle;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DSLContext dsl;

  private final List<TestIdentity> identities = new ArrayList<>();
  private TestIdentity visitor;
  private TestIdentity host;
  private StoredNotificationRows rows;

  @BeforeEach
  void setUp() {
    rows = new StoredNotificationRows(dsl);
    var owner = track(authTestSupport.createIdentity());
    visitor = track(authTestSupport.createHouseholdMemberIdentity(owner));
    host = track(authTestSupport.createIdentity());
  }

  @AfterEach
  void tearDown() {
    rows.deleteOf(identities.stream().map(VisitEndAccountDeletionRaceIT::accountOf).toList());
    identities.reversed().forEach(authTestSupport::deleteIdentity);
  }

  /**
   * The test holds the visit's row, which the end writes once it holds the visitor's Personal
   * Profile, so the end waits there holding the Profile; the deletion then waits behind it.
   */
  @Test
  @DisplayName(
      "Should end the visit and delete the Account when the visitor deletes its Account while the"
          + " host ends the visit")
  void shouldEndTheVisitAndDeleteTheAccountWhenTheVisitorDeletesItsAccountWhileTheHostEndsTheVisit()
      throws Exception {
    var visit = activeVisit();
    var visitRow =
        RowLockTarget.builder()
            .dataSource(dataSource)
            .table("profile_household_share")
            .rowId(visit.getId())
            .build();

    Outcome<?, ?> ended;
    Outcome<?, ?> deleted;
    try (var executor = TestFixtureWork.newExecutorRunningAsTestFixtureActor();
        var held = lockRow(visitRow)) {
      var ending =
          executor.submit(
              () -> sharing.endProfileShare(authTestSupport.identityOf(host), visit.getId()));
      awaitWaitersBehind(jdbcTemplate, held.backendPid(), 1);
      var deleting =
          executor.submit(
              () ->
                  accountLifecycle.deleteMyAccount(
                      authTestSupport.freshIdentityOf(visitor), "DELETE"));
      awaitWaitersBehind(jdbcTemplate, held.backendPid(), 2);

      held.release();
      ended = ending.get(10, TimeUnit.SECONDS);
      deleted = deleting.get(10, TimeUnit.SECONDS);
    }

    assertThat(ended).isInstanceOf(Outcome.Accepted.class);
    assertThat(deleted).isEqualTo(Outcome.accepted(visitor.account().getId()));
    assertThat(userAccountRepository.findById(visitor.account().getId())).isEmpty();
    assertThat(rows.of(accountOf(visitor))).isEmpty();
    assertThat(rows.about(SubjectReference.profileShare(visit.getId()))).isEmpty();
  }

  /** The visitor's Personal Profile visits the host's Household. */
  private ProfileHouseholdShare activeVisit() {
    return transactionTemplate.execute(
        _ ->
            shareRepository.saveAndFlush(
                ProfileHouseholdShare.builder()
                    .profileId(visitor.profile().getId())
                    .householdId(host.household().getId())
                    .status(ProfileShareStatus.ACTIVE)
                    .build()));
  }

  private TestIdentity track(TestIdentity identity) {
    identities.add(identity);
    return identity;
  }

  private static RecipientReference accountOf(TestIdentity identity) {
    return RecipientReference.account(identity.account().getId());
  }
}
