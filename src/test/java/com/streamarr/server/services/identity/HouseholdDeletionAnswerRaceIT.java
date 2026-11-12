package com.streamarr.server.services.identity;

import static com.streamarr.server.support.PostgresLockTestSupport.awaitWaitersBehind;
import static com.streamarr.server.support.PostgresLockTestSupport.lockRow;
import static com.streamarr.server.support.PostgresLockTestSupport.waitersBehind;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.events.notification.RecipientReference;
import com.streamarr.server.services.events.notification.SubjectReference;
import com.streamarr.server.services.events.notification.TellNotificationEvent;
import com.streamarr.server.services.identity.HouseholdDeletionService.DeleteEmptyHouseholdCommand;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupport.TestIdentity;
import com.streamarr.server.support.PostgresLockTestSupport.RowLockTarget;
import com.streamarr.server.support.ShareSteps;
import com.streamarr.server.support.SpecifiedOnlyNotificationKinds;
import com.streamarr.server.support.SpecifiedOnlyNotificationKinds.HouseholdNewsSnapshot;
import com.streamarr.server.support.StoredNotificationRows;
import com.streamarr.server.support.TestFixtureWork;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deleting a Household dismisses every Notification about the shares into it (ADR 0063 §Dismissal).
 * So an offer into the Household that a ServerAdmin answers while the Household is deleted leaves
 * no Notification about the offer, even when nobody holds a request about it.
 */
@Tag("IntegrationTest")
@DisplayName("Household Deletion Answer Race Integration Tests")
class HouseholdDeletionAnswerRaceIT extends AbstractIntegrationTest {

  private static final String ANY_STATEMENT = "%";

  @Autowired private ProfileSharingService sharing;
  @Autowired private HouseholdDeletionService householdDeletion;
  @Autowired private HouseholdRepository householdRepository;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private ProfileManagerRepository profileManagerRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private ApplicationEventPublisher eventPublisher;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DSLContext dsl;

  private final List<TestIdentity> identities = new ArrayList<>();
  private TestIdentity sam;
  private TestIdentity serverAdmin;
  private Household lakeside;
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
    serverAdmin = track(authTestSupport.createAdminIdentity());
    lakeside = householdRepository.saveAndFlush(Household.builder().name("Lakeside").build());
  }

  @AfterEach
  void tearDown() {
    rows.deleteOf(identities.stream().map(HouseholdDeletionAnswerRaceIT::accountOf).toList());
    if (householdRepository.existsById(lakeside.getId())) {
      deleteLakeside();
    }

    identities.reversed().forEach(authTestSupport::deleteIdentity);
  }

  /**
   * Lakeside has no Account, so nobody holds a request about the offer. The test holds a
   * Notification about Lakeside, which the deletion dismisses after the shares into Lakeside and
   * before it deletes the Household, so the deletion waits there while the ServerAdmin rejects the
   * offer.
   */
  @Test
  @DisplayName(
      "Should leave no Notification about an offer when a ServerAdmin rejects it while its"
          + " Household is deleted")
  void shouldLeaveNoNotificationAboutAnOfferWhenAServerAdminRejectsItWhileItsHouseholdIsDeleted()
      throws Exception {
    var offer = offerToLakeside(steps.profileManagedBy("Kai", sam, sam));
    var newsAboutLakeside =
        RowLockTarget.builder()
            .dataSource(dataSource)
            .table("notification")
            .rowId(tellSamAboutLakeside())
            .build();

    Outcome<?, ?> deleted;
    try (var executor = TestFixtureWork.newExecutorRunningAsTestFixtureActor();
        var news = lockRow(newsAboutLakeside)) {
      var deleting = executor.submit(this::deleteLakeside);
      awaitWaitersBehind(jdbcTemplate, news.backendPid(), 1);
      var rejecting =
          executor.submit(
              () ->
                  sharing.rejectProfileShare(
                      authTestSupport.freshIdentityOf(serverAdmin), offer.getId()));
      await()
          .atMost(Duration.ofSeconds(10))
          .until(
              () ->
                  rejecting.isDone()
                      || waitersBehind(jdbcTemplate, news.backendPid(), ANY_STATEMENT) == 2);

      news.release();
      deleted = deleting.get(10, TimeUnit.SECONDS);
      rejecting.get(10, TimeUnit.SECONDS);
    }

    assertThat(deleted).isEqualTo(Outcome.accepted(lakeside.getId()));
    assertThat(rows.about(SubjectReference.profileShare(offer.getId()))).isEmpty();
  }

  /** Sam offers the Profile to Lakeside with a code that a ServerAdmin issued for it. */
  private ProfileHouseholdShare offerToLakeside(Profile profile) {
    var code = authTestSupport.shareCodeFor(serverAdmin, lakeside.getId());
    return sharing
        .offerProfileShare(
            authTestSupport.identityOf(sam),
            profile.getId(),
            ShareCodeCommand.builder().code(code).ipAddress(ShareSteps.IP_ADDRESS).build())
        .fold(
            share -> share,
            rejection -> {
              throw new IllegalStateException("The offer was refused: " + rejection);
            });
  }

  /** Tells Sam news about Lakeside, as a later kind about a Household will; answers its id. */
  private UUID tellSamAboutLakeside() {
    var subject = SubjectReference.household(lakeside.getId());
    transactionTemplate.executeWithoutResult(
        _ ->
            eventPublisher.publishEvent(
                TellNotificationEvent.builder()
                    .kind(SpecifiedOnlyNotificationKinds.HOUSEHOLD_NEWS)
                    .subject(subject)
                    .recipients(Set.of(accountOf(sam)))
                    .snapshot(new HouseholdNewsSnapshot("Lakeside"))
                    .at(Instant.now())
                    .build()));
    return rows.about(subject).getFirst().id();
  }

  private Outcome<?, ?> deleteLakeside() {
    return householdDeletion.deleteEmptyHousehold(
        authTestSupport.freshIdentityOf(serverAdmin),
        DeleteEmptyHouseholdCommand.builder()
            .householdId(lakeside.getId())
            .reason("closing")
            .build());
  }

  private TestIdentity track(TestIdentity identity) {
    identities.add(identity);
    return identity;
  }

  private static RecipientReference accountOf(TestIdentity identity) {
    return RecipientReference.account(identity.account().getId());
  }
}
