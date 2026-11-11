package com.streamarr.server.repositories.notification;

import static com.streamarr.server.support.PostgresLockTestSupport.awaitLatch;
import static com.streamarr.server.support.PostgresLockTestSupport.waitersBehind;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.notification.Notification;
import com.streamarr.server.domain.notification.NotificationDraft;
import com.streamarr.server.domain.notification.NotificationForm;
import com.streamarr.server.domain.notification.NotificationKind;
import com.streamarr.server.domain.notification.ProfileShareAnswer;
import com.streamarr.server.domain.notification.ProfileShareEndedBy;
import com.streamarr.server.domain.notification.ProfileShareFacts;
import com.streamarr.server.domain.notification.UnreadNews;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.PostgresLockTestSupport;
import com.streamarr.server.support.PostgresLockTestSupport.RowLockTarget;
import com.streamarr.server.support.TestFixtureWork;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * How Notifications are kept (ADR 0063): one row for each recipient Account, at most one unread
 * news of a kind about one share for each recipient, and no row left behind when its recipient or
 * its share is deleted.
 */
@Tag("IntegrationTest")
@DisplayName("Notification Repository Integration Tests")
class NotificationRepositoryIT extends AbstractIntegrationTest {

  private static final Instant EVENT = Instant.now().truncatedTo(ChronoUnit.MICROS);

  @Autowired private NotificationRepository notificationRepository;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private ProfileManagerRepository profileManagerRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DSLContext dsl;
  @Autowired private DataSource dataSource;

  private final List<AuthTestSupport.TestIdentity> identities = new ArrayList<>();
  private AuthTestSupport.TestIdentity sam;
  private AuthTestSupport.TestIdentity riverside;

  @BeforeEach
  void setUp() {
    sam = track(authTestSupport.createIdentity());
    riverside = track(authTestSupport.createIdentity());
  }

  @AfterEach
  void tearDown() {
    identities.reversed().forEach(authTestSupport::deleteIdentity);
  }

  @Test
  @DisplayName(
      "Should keep one unread news per recipient, kind and share when the kind is told again")
  void shouldKeepOneUnreadNewsPerRecipientKindAndShareWhenTheKindIsToldAgain() {
    var share = offerOf(managedProfile("Ollie"), sam);
    var first = upsert(answered(sam, share).at(EVENT).build());

    var again = upsert(answered(sam, share).at(EVENT.plusSeconds(60)).build());

    assertThat(first).isInstanceOf(UnreadNews.Made.class);
    assertThat(again)
        .isEqualTo(
            new UnreadNews.Moved<>(
                first.notificationId(),
                ProfileShareFacts.answered("Ollie", ProfileShareAnswer.ACCEPTED)));
    assertThat(notificationsOf(sam))
        .singleElement()
        .satisfies(
            notification -> {
              assertThat(notification.getLatestEventAt()).isEqualTo(EVENT.plusSeconds(60));
              assertThat(notification.getForm()).isEqualTo(NotificationForm.NEWS);
              assertThat(notification.getReadAt()).isEmpty();
            });
  }

  @Test
  @DisplayName("Should make new news when the recipient read the earlier news of the kind")
  void shouldMakeNewNewsWhenTheRecipientReadTheEarlierNewsOfTheKind() {
    var share = offerOf(managedProfile("Ollie"), sam);
    var read = upsert(answered(sam, share).at(EVENT).build());
    markRead(read.notificationId());

    var later = upsert(answered(sam, share).at(EVENT.plusSeconds(60)).build());

    assertThat(later).isInstanceOf(UnreadNews.Made.class);
    assertThat(later.notificationId()).isNotEqualTo(read.notificationId());
    assertThat(notificationsOf(sam)).hasSize(2);
  }

  @Test
  @DisplayName(
      "Should keep news of different kinds about one share side by side when each kind is told")
  void shouldKeepNewsOfDifferentKindsAboutOneShareSideBySideWhenEachKindIsTold() {
    var share = offerOf(managedProfile("Ollie"), sam);
    var answer = upsert(answered(sam, share).at(EVENT).build());

    var end =
        upsert(
            draft(sam, share)
                .kind(NotificationKind.PROFILE_SHARE_ENDED)
                .facts(ProfileShareFacts.ended("Ollie", ProfileShareEndedBy.HOUSEHOLD_SIDE))
                .at(EVENT.plusSeconds(60))
                .build());

    assertThat(end).isInstanceOf(UnreadNews.Made.class);
    assertThat(notificationsOf(sam))
        .extracting(Notification::getId)
        .containsExactlyInAnyOrder(answer.notificationId(), end.notificationId());
  }

  @Test
  @DisplayName("Should store each request apart when two requests about one share are made")
  void shouldStoreEachRequestApartWhenTwoRequestsAboutOneShareAreMade() {
    var share = offerOf(managedProfile("Ollie"), sam);

    var first = insert(offered(riverside, share).at(EVENT).build());
    var second = insert(offered(riverside, share).at(EVENT).build());

    assertThat(first).isNotEqualTo(second);
    assertThat(notificationsOf(riverside))
        .hasSize(2)
        .allSatisfy(
            notification -> assertThat(notification.getForm()).isEqualTo(NotificationForm.REQUEST));
  }

  @Test
  @DisplayName("Should store the facts it is given when the facts of a held news are replaced")
  void shouldStoreTheFactsItIsGivenWhenTheFactsOfAHeldNewsAreReplaced() {
    var share = offerOf(managedProfile("Ollie"), sam);
    var made = upsert(answered(sam, share).at(EVENT).build());

    transactionTemplate.executeWithoutResult(
        _ ->
            notificationRepository.replaceFacts(
                made.notificationId(),
                ProfileShareFacts.answered("Ollie Rose", ProfileShareAnswer.ACCEPTED)));

    assertThat(notificationsOf(sam))
        .singleElement()
        .extracting(Notification::getFacts)
        .isEqualTo(ProfileShareFacts.answered("Ollie Rose", ProfileShareAnswer.ACCEPTED));
  }

  @Test
  @DisplayName(
      "Should keep one unread news when two events of the kind about one share arrive at once")
  void shouldKeepOneUnreadNewsWhenTwoEventsOfTheKindAboutOneShareArriveAtOnce() throws Exception {
    var share = offerOf(managedProfile("Ollie"), sam);
    var firstHolds = new CountDownLatch(1);
    var releaseFirst = new CountDownLatch(1);
    var firstPid = new AtomicInteger();

    UnreadNews<ProfileShareFacts> first;
    UnreadNews<ProfileShareFacts> second;
    try (var executor = TestFixtureWork.newExecutorRunningAsTestFixtureActor()) {
      var firstEvent =
          executor.submit(
              () ->
                  transactionTemplate.execute(
                      _ -> {
                        var made =
                            notificationRepository.upsertUnreadNews(
                                answered(sam, share).at(EVENT).build());
                        firstPid.set(backendPid());
                        firstHolds.countDown();
                        awaitLatch(releaseFirst);
                        return made;
                      }));
      awaitLatch(firstHolds);
      var secondEvent =
          executor.submit(() -> upsert(answered(sam, share).at(EVENT.plusSeconds(1)).build()));
      await()
          .atMost(Duration.ofSeconds(10))
          .until(() -> waitersBehind(jdbcTemplate, firstPid.get(), "insert%notification%") == 1);

      releaseFirst.countDown();
      first = firstEvent.get(10, TimeUnit.SECONDS);
      second = secondEvent.get(10, TimeUnit.SECONDS);
    }

    assertThat(first).isInstanceOf(UnreadNews.Made.class);
    assertThat(second).isInstanceOf(UnreadNews.Moved.class);
    assertThat(second.notificationId()).isEqualTo(first.notificationId());
    assertThat(notificationsOf(sam)).hasSize(1);
  }

  // Account transfers, removals and promotions lock Account rows FOR UPDATE. A share write tells
  // Accounts it does not lock, so a Notification must not wait on its recipient's row.
  @Test
  @DisplayName(
      "Should make a Notification without waiting when another transaction holds its recipient's"
          + " Account row")
  void shouldMakeANotificationWithoutWaitingWhenAnotherTransactionHoldsItsRecipientsAccountRow()
      throws Exception {
    var share = offerOf(managedProfile("Ollie"), sam);

    try (var held =
            PostgresLockTestSupport.lockRow(
                RowLockTarget.builder()
                    .dataSource(dataSource)
                    .table("user_account")
                    .rowId(riverside.account().getId())
                    .build());
        var executor = TestFixtureWork.newExecutorRunningAsTestFixtureActor()) {
      var request = executor.submit(() -> insert(offered(riverside, share).at(EVENT).build()));
      var news = executor.submit(() -> upsert(answered(riverside, share).at(EVENT).build()));

      assertThat(request.get(5, TimeUnit.SECONDS)).isNotNull();
      assertThat(news.get(5, TimeUnit.SECONDS)).isInstanceOf(UnreadNews.Made.class);
      held.release();
    }
  }

  @Test
  @DisplayName("Should delete the Notifications of an Account when the Account is deleted")
  void shouldDeleteTheNotificationsOfAnAccountWhenTheAccountIsDeleted() {
    var kai = track(authTestSupport.createIdentity());
    var share = offerOf(managedProfile("Ollie"), sam);
    insert(offered(kai, share).at(EVENT).build());

    authTestSupport.deleteIdentity(kai);

    assertThat(notificationsAbout(share)).isEmpty();
  }

  @Test
  @DisplayName("Should delete the Notifications about a share when its Profile is deleted")
  void shouldDeleteTheNotificationsAboutAShareWhenItsProfileIsDeleted() {
    var profile = managedProfile("Ollie");
    var share = offerOf(profile, sam);
    insert(offered(riverside, share).at(EVENT).build());
    upsert(answered(sam, share).at(EVENT).build());

    transactionTemplate.executeWithoutResult(
        _ -> {
          profileRepository.deleteById(profile.getId());
          profileRepository.flush();
        });

    assertThat(notificationsAbout(share)).isEmpty();
  }

  @Test
  @DisplayName("Should delete the Notifications about a share when its Household is deleted")
  void shouldDeleteTheNotificationsAboutAShareWhenItsHouseholdIsDeleted() {
    var share = offerOf(managedProfile("Ollie"), sam);
    upsert(answered(sam, share).at(EVENT).build());

    authTestSupport.deleteIdentity(riverside);

    assertThat(notificationsAbout(share)).isEmpty();
  }

  @Test
  @DisplayName(
      "Should keep the Notifications about a share when the Account that made the offer is"
          + " deleted")
  void shouldKeepTheNotificationsAboutAShareWhenTheAccountThatMadeTheOfferIsDeleted() {
    var kai = track(authTestSupport.createIdentity());
    var share = offerOf(managedProfile("Ollie"), kai);
    var request = insert(offered(riverside, share).at(EVENT).build());

    authTestSupport.deleteIdentity(kai);

    assertThat(notificationsAbout(share)).extracting(Notification::getId).containsExactly(request);
  }

  @Test
  @DisplayName(
      "Should read the unread news before the cutoff in pages of the limit, each after the last id"
          + " of the one before, when there is more than a page of it")
  void shouldReadTheUnreadNewsBeforeTheCutoffInPagesOfTheLimitWhenThereIsMoreThanAPage() {
    var old = EVENT.minus(Duration.ofDays(100));
    var arranged = new ArrayList<UUID>();
    for (var made = 0; made < 5; made++) {
      arranged.add(
          insert(answered(sam, offerOf(managedProfile("Ollie " + made), sam)).at(old).build()));
    }

    var read = new ArrayList<UUID>();
    var after = Optional.<UUID>empty();
    while (true) {
      var page =
          notificationRepository.findUnreadNewsBefore(EVENT.minus(Duration.ofDays(90)), after, 2);
      assertThat(page).hasSizeLessThanOrEqualTo(2);
      if (page.isEmpty()) {
        break;
      }

      page.forEach(notification -> read.add(notification.getId()));
      after = Optional.of(page.getLast().getId());
    }

    assertThat(read).doesNotHaveDuplicates().containsAll(arranged);
  }

  private UUID insert(NotificationDraft<NotificationKind, ProfileShareFacts> draft) {
    return transactionTemplate.execute(_ -> notificationRepository.insert(draft));
  }

  private UnreadNews<ProfileShareFacts> upsert(
      NotificationDraft<NotificationKind, ProfileShareFacts> draft) {
    return transactionTemplate.execute(_ -> notificationRepository.upsertUnreadNews(draft));
  }

  private void markRead(UUID notificationId) {
    transactionTemplate.executeWithoutResult(
        _ -> {
          var notification = notificationRepository.findById(notificationId).orElseThrow();
          notification.setReadAt(EVENT);
          notificationRepository.saveAndFlush(notification);
        });
  }

  private int backendPid() {
    return dsl.select(DSL.field("pg_backend_pid()", SQLDataType.INTEGER)).fetchSingle().value1();
  }

  private List<Notification> notificationsOf(AuthTestSupport.TestIdentity recipient) {
    return notificationRepository.findAll().stream()
        .filter(
            notification ->
                notification.getRecipientAccountId().equals(recipient.account().getId()))
        .toList();
  }

  private List<Notification> notificationsAbout(ProfileHouseholdShare share) {
    return notificationRepository.findAll().stream()
        .filter(notification -> notification.getShareId().equals(share.getId()))
        .toList();
  }

  private static NotificationDraft.NotificationDraftBuilder<NotificationKind, ProfileShareFacts>
      draft(AuthTestSupport.TestIdentity recipient, ProfileHouseholdShare share) {
    return NotificationDraft.<NotificationKind, ProfileShareFacts>builder()
        .recipientAccountId(recipient.account().getId())
        .subjectId(share.getId());
  }

  private static NotificationDraft.NotificationDraftBuilder<NotificationKind, ProfileShareFacts>
      offered(AuthTestSupport.TestIdentity recipient, ProfileHouseholdShare share) {
    return draft(recipient, share)
        .kind(NotificationKind.PROFILE_SHARE_OFFERED)
        .facts(ProfileShareFacts.offered("Ollie"));
  }

  private static NotificationDraft.NotificationDraftBuilder<NotificationKind, ProfileShareFacts>
      answered(AuthTestSupport.TestIdentity recipient, ProfileHouseholdShare share) {
    return draft(recipient, share)
        .kind(NotificationKind.PROFILE_SHARE_ANSWERED)
        .facts(ProfileShareFacts.answered("Ollie", ProfileShareAnswer.ACCEPTED));
  }

  /** A Profile Sam alone manages, available in Sam's Household. */
  private Profile managedProfile(String name) {
    return transactionTemplate.execute(
        _ -> {
          var profile =
              profileRepository.saveAndFlush(
                  ProfileFixture.defaultProfileBuilder()
                      .name(name)
                      .householdId(sam.household().getId())
                      .build());
          profileManagerRepository.saveAndFlush(
              ProfileManager.builder()
                  .accountId(sam.account().getId())
                  .profileId(profile.getId())
                  .build());
          shareRepository.saveAndFlush(
              ProfileHouseholdShare.builder()
                  .profileId(profile.getId())
                  .householdId(sam.household().getId())
                  .status(ProfileShareStatus.ACTIVE)
                  .build());
          return profile;
        });
  }

  /** The offer of the Profile to Riverside's Household, made by the offerer. */
  private ProfileHouseholdShare offerOf(Profile profile, AuthTestSupport.TestIdentity offerer) {
    return transactionTemplate.execute(
        _ ->
            shareRepository.saveAndFlush(
                ProfileHouseholdShare.builder()
                    .profileId(profile.getId())
                    .householdId(riverside.household().getId())
                    .status(ProfileShareStatus.PENDING)
                    .offeredByAccountId(offerer.account().getId())
                    .expiresAt(EVENT.plus(Duration.ofDays(7)))
                    .build()));
  }

  private AuthTestSupport.TestIdentity track(AuthTestSupport.TestIdentity identity) {
    identities.add(identity);
    return identity;
  }
}
