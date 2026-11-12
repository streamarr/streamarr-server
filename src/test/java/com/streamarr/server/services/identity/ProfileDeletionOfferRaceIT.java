package com.streamarr.server.services.identity;

import static com.streamarr.server.support.PostgresLockTestSupport.awaitWaitersBehind;
import static com.streamarr.server.support.PostgresLockTestSupport.lockRow;
import static com.streamarr.server.support.PostgresLockTestSupport.waitersBehind;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.notification.RecipientReference;
import com.streamarr.server.domain.notification.SubjectReference;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.identity.AccountLifecycleService.AdministrativelyDeleteAccountCommand;
import com.streamarr.server.services.identity.AccountLifecycleService.ProfileCleanup;
import com.streamarr.server.services.identity.HouseholdDeletionService.DeleteLastAccountAndHouseholdCommand;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupport.TestIdentity;
import com.streamarr.server.support.PostgresLockTestSupport.RowLockTarget;
import com.streamarr.server.support.ShareSteps;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * An offer holds its Profile from its first statement until it commits, and deleting a Profile
 * dismisses every Notification about its shares (ADR 0063 §Dismissal). So a Profile deleted while
 * an offer of it commits leaves no Notification about that offer, whichever way it is deleted.
 */
@Tag("IntegrationTest")
@DisplayName("Profile Deletion Offer Race Integration Tests")
class ProfileDeletionOfferRaceIT extends AbstractIntegrationTest {

  private static final String OFFER_COMMIT = "commit";

  @Autowired private ProfileSharingService sharing;
  @Autowired private ProfileLifecycleService profileLifecycle;
  @Autowired private AccountLifecycleService accountLifecycle;
  @Autowired private HouseholdDeletionService householdDeletion;
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
    serverAdmin = track(authTestSupport.createAdminIdentity());
  }

  @AfterEach
  void tearDown() {
    rows.deleteOf(identities.stream().map(ProfileDeletionOfferRaceIT::accountOf).toList());
    identities.reversed().forEach(authTestSupport::deleteIdentity);
  }

  /**
   * The test holds the guard of the offer's target Household, which the offer takes at its commit,
   * so the offer waits there holding its Profile, its share and its requests; the deletion then
   * waits for the Profile behind it.
   */
  @ParameterizedTest
  @EnumSource(ProfileDeletion.class)
  @DisplayName(
      "Should leave no Notification about an offer when its Profile is deleted while the offer"
          + " commits")
  void shouldLeaveNoNotificationAboutAnOfferWhenItsProfileIsDeletedWhileTheOfferCommits(
      ProfileDeletion deletion) throws Exception {
    var doomed = deletion.doomedProfile(this);
    var code = authTestSupport.shareCodeFor(riverside);
    var guardOfTheTarget =
        RowLockTarget.builder()
            .dataSource(dataSource)
            .table("household_guard")
            .keyColumn("household_id")
            .rowId(riverside.household().getId())
            .build();

    ProfileHouseholdShare offer;
    Outcome<?, ?> deleted;
    try (var executor = TestFixtureWork.newExecutorRunningAsTestFixtureActor();
        var guard = lockRow(guardOfTheTarget)) {
      var offering = executor.submit(() -> offer(doomed.profile(), code));
      awaitWaitersBehind(jdbcTemplate, guard.backendPid(), 1);
      assertThat(waitersBehind(jdbcTemplate, guard.backendPid(), OFFER_COMMIT))
          .as("the offer waits at its commit")
          .isOne();
      var deleting = executor.submit(() -> deletion.delete(this, doomed));
      awaitWaitersBehind(jdbcTemplate, guard.backendPid(), 2);

      guard.release();
      offer = offering.get(10, TimeUnit.SECONDS);
      deleted = deleting.get(10, TimeUnit.SECONDS);
    }

    assertThat(deleted).isInstanceOf(Outcome.Accepted.class);
    assertThat(profileRepository.findById(doomed.profile().getId())).isEmpty();
    assertThat(rows.about(SubjectReference.profileShare(offer.getId()))).isEmpty();
    assertThat(rows.of(accountOf(riverside))).isEmpty();
  }

  /** How the Profile is deleted. */
  enum ProfileDeletion {
    BY_A_SERVER_ADMIN,
    WITH_ITS_ACCOUNT,
    WITH_ITS_HOUSEHOLD;

    Doomed doomedProfile(ProfileDeletionOfferRaceIT test) {
      return switch (this) {
        case BY_A_SERVER_ADMIN ->
            new Doomed(test.steps.profileManagedBy("Kai", test.sam, test.sam), test.sam);
        case WITH_ITS_ACCOUNT -> {
          var brook = test.track(test.authTestSupport.createHouseholdMemberIdentity(test.sam));
          yield new Doomed(brook.profile(), brook);
        }
        case WITH_ITS_HOUSEHOLD -> {
          var lakeside = test.track(test.authTestSupport.createIdentity());
          yield new Doomed(test.steps.profileManagedBy("Ollie", lakeside, lakeside), lakeside);
        }
      };
    }

    Outcome<?, ?> delete(ProfileDeletionOfferRaceIT test, Doomed doomed) {
      var actor = test.authTestSupport.freshIdentityOf(test.serverAdmin);
      return switch (this) {
        case BY_A_SERVER_ADMIN ->
            test.profileLifecycle.administrativelyDeleteProfile(
                actor, doomed.profile().getId(), "a duplicate");
        case WITH_ITS_ACCOUNT ->
            test.accountLifecycle.administrativelyDeleteAccount(
                actor,
                AdministrativelyDeleteAccountCommand.builder()
                    .accountId(doomed.owner().account().getId())
                    .profileCleanup(ProfileCleanup.ERASE_PROFILE)
                    .reason("left")
                    .build());
        case WITH_ITS_HOUSEHOLD ->
            test.householdDeletion.deleteLastAccountAndHousehold(
                actor,
                DeleteLastAccountAndHouseholdCommand.builder()
                    .householdId(doomed.owner().household().getId())
                    .reason("closing")
                    .build());
      };
    }
  }

  /** The Profile to delete, and the identity whose Account or Household goes with it. */
  private record Doomed(Profile profile, TestIdentity owner) {}

  /** A ServerAdmin offers the Profile to Riverside with a code that Riverside issued. */
  private ProfileHouseholdShare offer(Profile profile, String code) {
    return sharing
        .offerProfileShare(
            authTestSupport.identityOf(serverAdmin),
            profile.getId(),
            ShareCodeCommand.builder().code(code).ipAddress(ShareSteps.IP_ADDRESS).build())
        .fold(
            share -> share,
            rejection -> {
              throw new IllegalStateException("The offer was refused: " + rejection);
            });
  }

  private TestIdentity track(TestIdentity identity) {
    identities.add(identity);
    return identity;
  }

  private static RecipientReference accountOf(TestIdentity identity) {
    return RecipientReference.account(identity.account().getId());
  }
}
