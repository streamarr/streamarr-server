package com.streamarr.server.services.identity;

import static com.streamarr.server.support.PostgresLockTestSupport.awaitWaitersBehind;
import static com.streamarr.server.support.PostgresLockTestSupport.lockRow;
import static com.streamarr.server.support.PostgresLockTestSupport.waitersBehind;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.events.notification.RecipientReference;
import com.streamarr.server.services.events.notification.SharingNotificationKinds;
import com.streamarr.server.services.events.notification.SubjectReference;
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
 * commits, and a demotion changes the role and then, in its own transaction, dismisses the demoted
 * Account's waiting requests about the offers still pending into its Household (ADR 0063
 * §Dismissal). So an offer and a demotion that run at the same moment leave the demoted Account no
 * waiting request, whichever commits first.
 */
@Tag("IntegrationTest")
@DisplayName("Offer Demotion Race Integration Tests")
class OfferDemotionRaceIT extends AbstractIntegrationTest {

  private static final String ANY_STATEMENT = "%";

  @Autowired private ProfileSharingService sharing;
  @Autowired private AccountAdministrationService accountAdministration;
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
    rows.deleteOf(identities.stream().map(OfferDemotionRaceIT::accountOf).toList());
    identities.reversed().forEach(authTestSupport::deleteIdentity);
  }

  /**
   * The demotion changes the role and then parks on a share code that the demoted Account issued,
   * which it invalidates before it commits; the offer then reads the HouseholdAdmins and waits
   * behind the changed row, so it reads the role the demotion commits.
   */
  @Test
  @DisplayName("Should leave no waiting request when the demotion commits first")
  void shouldLeaveNoWaitingRequestWhenTheDemotionCommitsFirst() throws Exception {
    var kai = steps.profileManagedBy("Kai", sam, sam);
    authTestSupport.shareCodeFor(riversideAdmin);
    var codesOfTheDemoted =
        RowLockTarget.builder()
            .dataSource(dataSource)
            .table("share_code")
            .keyColumn("issuer_account_id")
            .rowId(riversideAdmin.account().getId())
            .build();

    ProfileHouseholdShare offer;
    try (var executor = TestFixtureWork.newExecutorRunningAsTestFixtureActor();
        var codes = lockRow(codesOfTheDemoted)) {
      var demotion = executor.submit(() -> demote(riversideAdmin));
      awaitWaitersBehind(jdbcTemplate, codes.backendPid(), 1);
      var offering = executor.submit(() -> steps.offer(sam, kai, riverside));
      await()
          .atMost(Duration.ofSeconds(10))
          .until(() -> waitersBehind(jdbcTemplate, codes.backendPid(), ANY_STATEMENT) == 2);
      assertThat(offering.isDone()).isFalse();

      codes.release();
      demotion.get(10, TimeUnit.SECONDS);
      offer = offering.get(10, TimeUnit.SECONDS);
    }

    assertThat(waitingRequests(offer, riversideAdmin)).isEmpty();
    assertThat(waitingRequests(offer, riverside)).hasSize(1);
  }

  /**
   * The test holds the HouseholdAdmin's row {@code FOR SHARE}, so the demotion waits to change the
   * role while the offer, whose read is also {@code FOR SHARE}, tells the Account and commits; the
   * demotion's dismissal then finds the new request.
   */
  @Test
  @DisplayName("Should leave no waiting request when the offer commits first")
  void shouldLeaveNoWaitingRequestWhenTheOfferCommitsFirst() throws Exception {
    var kai = steps.profileManagedBy("Kai", sam, sam);
    var rowOfTheDemoted =
        RowLockTarget.builder()
            .dataSource(dataSource)
            .table("user_account")
            .rowId(riversideAdmin.account().getId())
            .shared(true)
            .build();

    ProfileHouseholdShare offer;
    try (var executor = TestFixtureWork.newExecutorRunningAsTestFixtureActor();
        var row = lockRow(rowOfTheDemoted)) {
      var demotion = executor.submit(() -> demote(riversideAdmin));
      awaitWaitersBehind(jdbcTemplate, row.backendPid(), 1);
      offer = executor.submit(() -> steps.offer(sam, kai, riverside)).get(10, TimeUnit.SECONDS);
      assertThat(waitingRequests(offer, riversideAdmin)).hasSize(1);

      row.release();
      demotion.get(10, TimeUnit.SECONDS);
    }

    assertThat(waitingRequests(offer, riversideAdmin)).isEmpty();
    assertThat(waitingRequests(offer, riverside)).hasSize(1);
  }

  private Outcome<?, ?> demote(TestIdentity householdAdmin) {
    var outcome =
        accountAdministration.revokeHouseholdAdmin(
            authTestSupport.freshIdentityOf(serverAdmin), householdAdmin.account().getId());
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
