package com.streamarr.server.services.identity;

import static com.streamarr.server.support.PostgresLockTestSupport.awaitWaitersBehind;
import static com.streamarr.server.support.PostgresLockTestSupport.lockRow;
import static com.streamarr.server.support.PostgresLockTestSupport.waitersBehind;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.SourceHouseholdAccess;
import com.streamarr.server.domain.notification.RecipientReference;
import com.streamarr.server.domain.notification.SubjectReference;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.events.notification.SharingNotificationKinds;
import com.streamarr.server.services.identity.AccountLifecycleService.TransferAccountCommand;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupport.TestIdentity;
import com.streamarr.server.support.PostgresLockTestSupport.RowLockTarget;
import com.streamarr.server.support.ShareSteps;
import com.streamarr.server.support.StoredNotificationRows;
import com.streamarr.server.support.TestFixtureWork;
import java.time.Duration;
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
 * An offer reads the target Household's HouseholdAdmins {@code FOR SHARE} and holds them until it
 * commits, and a move of a HouseholdAdmin's Account to another Household changes the Account's row
 * and then, in its own transaction, dismisses its waiting requests about the offers still pending
 * into the Household it leaves (ADR 0063 §Dismissal). So an offer and a move that run at the same
 * moment leave the moved Account no waiting request, whichever commits first.
 */
@Tag("IntegrationTest")
@DisplayName("Offer Move Race Integration Tests")
class OfferMoveRaceIT extends AbstractIntegrationTest {

  private static final String ANY_STATEMENT = "%";

  @Autowired private ProfileSharingService sharing;
  @Autowired private AccountLifecycleService accountLifecycle;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private ProfileManagerRepository profileManagerRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DSLContext dsl;

  private final List<TestIdentity> identities = new ArrayList<>();
  private TestIdentity sam;
  private TestIdentity riverside;
  private TestIdentity riversideAdmin;
  private TestIdentity serverAdmin;
  private ShareSteps steps;
  private StoredNotificationRows rows;

  @BeforeEach
  void setUp() {
    rows = new StoredNotificationRows(dsl);
    steps =
        ShareSteps.builder()
            .sharing(sharing)
            .auth(authTestSupport)
            .profiles(profileRepository)
            .managers(profileManagerRepository)
            .shares(shareRepository)
            .accounts(userAccountRepository)
            .transactions(transactionTemplate)
            .build();
    sam = track(authTestSupport.createIdentity());
    riverside = track(authTestSupport.createIdentity());
    riversideAdmin =
        steps.promoted(track(authTestSupport.createHouseholdMemberIdentity(riverside)));
    serverAdmin = track(authTestSupport.createAdminIdentity());
  }

  @AfterEach
  void tearDown() {
    rows.deleteOf(identities.stream().map(OfferMoveRaceIT::accountOf).toList());
    identities.reversed().forEach(authTestSupport::deleteIdentity);
  }

  /**
   * The test holds the HouseholdAdmin's row {@code FOR SHARE}, so the move waits to change the row
   * while the offer, whose read is also {@code FOR SHARE}, tells the Account and commits; the
   * move's dismissal then finds the new request.
   */
  @Test
  @DisplayName("Should leave no waiting request when the offer commits first")
  void shouldLeaveNoWaitingRequestWhenTheOfferCommitsFirst() throws Exception {
    var kai = steps.profileManagedBy("Kai", sam, sam);
    var rowOfTheMoved =
        RowLockTarget.builder()
            .dataSource(dataSource)
            .table("user_account")
            .rowId(riversideAdmin.account().getId())
            .shared(true)
            .build();

    ProfileHouseholdShare offer;
    try (var executor = TestFixtureWork.newExecutorRunningAsTestFixtureActor();
        var row = lockRow(rowOfTheMoved)) {
      var move = executor.submit(() -> moveToSam(riversideAdmin));
      awaitWaitersBehind(jdbcTemplate, row.backendPid(), 1);
      offer = executor.submit(() -> steps.offer(sam, kai, riverside)).get(10, TimeUnit.SECONDS);
      assertThat(waitingRequests(offer, riversideAdmin)).hasSize(1);

      row.release();
      move.get(10, TimeUnit.SECONDS);
    }

    assertThat(waitingRequests(offer, riversideAdmin)).isEmpty();
    assertThat(waitingRequests(offer, riverside)).hasSize(1);
  }

  /**
   * The move changes the Account's row and dismisses, then parks on its Personal Profile, which it
   * moves next; the offer then reads the HouseholdAdmins and waits behind the changed row, so it
   * reads the Household the move commits.
   */
  @Test
  @DisplayName("Should leave no waiting request when the move commits first")
  void shouldLeaveNoWaitingRequestWhenTheMoveCommitsFirst() throws Exception {
    var kai = steps.profileManagedBy("Kai", sam, sam);
    var profileOfTheMoved =
        RowLockTarget.builder()
            .dataSource(dataSource)
            .table("profile")
            .rowId(riversideAdmin.profile().getId())
            .shared(true)
            .build();

    ProfileHouseholdShare offer;
    try (var executor = TestFixtureWork.newExecutorRunningAsTestFixtureActor();
        var profile = lockRow(profileOfTheMoved)) {
      var move = executor.submit(() -> moveToSam(riversideAdmin));
      awaitWaitersBehind(jdbcTemplate, profile.backendPid(), 1);
      var offering = executor.submit(() -> steps.offer(sam, kai, riverside));
      await()
          .atMost(Duration.ofSeconds(10))
          .until(() -> waitersBehind(jdbcTemplate, profile.backendPid(), ANY_STATEMENT) == 2);
      assertThat(offering.isDone()).isFalse();

      profile.release();
      move.get(10, TimeUnit.SECONDS);
      offer = offering.get(10, TimeUnit.SECONDS);
    }

    assertThat(waitingRequests(offer, riversideAdmin)).isEmpty();
    assertThat(waitingRequests(offer, riverside)).hasSize(1);
  }

  /** A ServerAdmin moves the Account to Sam's Household, where it keeps a visitor share. */
  private Outcome<?, ?> moveToSam(TestIdentity account) {
    var outcome =
        accountLifecycle.transferAccount(
            authTestSupport.freshIdentityOf(serverAdmin),
            TransferAccountCommand.builder()
                .accountId(account.account().getId())
                .destinationHouseholdId(sam.household().getId())
                .sourceHouseholdAccess(SourceHouseholdAccess.KEEP_AS_VISITOR)
                .reason("moved in with Sam")
                .build());
    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    return outcome;
  }

  /** The recipient's requests about the offer that no event has resolved. */
  private List<StoredNotificationRows.Row> waitingRequests(
      ProfileHouseholdShare offer, TestIdentity recipient) {
    return rows.about(SubjectReference.profileShare(offer.getId())).stream()
        .filter(row -> row.recipient().equals(accountOf(recipient)))
        .filter(row -> row.kind().equals(SharingNotificationKinds.PROFILE_SHARE_OFFERED.name()))
        .filter(row -> row.resolution().isEmpty())
        .toList();
  }

  private TestIdentity track(TestIdentity identity) {
    identities.add(identity);
    return identity;
  }

  private static RecipientReference accountOf(TestIdentity identity) {
    return RecipientReference.account(identity.account().getId());
  }
}
