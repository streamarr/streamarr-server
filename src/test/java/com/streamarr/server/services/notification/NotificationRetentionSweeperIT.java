package com.streamarr.server.services.notification;

import static com.streamarr.server.jooq.generated.tables.ProfileManager.PROFILE_MANAGER;
import static com.streamarr.server.jooq.generated.tables.ServerSettings.SERVER_SETTINGS;
import static com.streamarr.server.jooq.generated.tables.ShareNotification.SHARE_NOTIFICATION;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.notification.Notification;
import com.streamarr.server.domain.notification.NotificationKind;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.repositories.notification.ShareNotificationRepository;
import com.streamarr.server.services.identity.ProfileSharingService;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupport.TestIdentity;
import com.streamarr.server.support.ServerSettingsTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Retention (ADR 0063): history, the news that its recipient read and the requests that resolved,
 * and unread news that the guard hides go once their latest event is older than the retention
 * period. Unread news that the guard shows, and waiting requests, stay.
 */
@Tag("IntegrationTest")
@DisplayName("Notification Retention Sweeper Integration Tests")
class NotificationRetentionSweeperIT extends AbstractIntegrationTest {

  private static final Duration PAST_THE_PERIOD = Duration.ofDays(91);
  private static final Duration WITHIN_THE_PERIOD = Duration.ofDays(89);

  @Autowired private NotificationRetentionSweeper sweeper;
  @Autowired private ShareNotificationRepository notificationRepository;
  @Autowired private NotificationService notificationService;
  @Autowired private ProfileSharingService sharing;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private ProfileManagerRepository profileManagerRepository;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DSLContext dsl;

  private final List<TestIdentity> identities = new ArrayList<>();
  private ShareSteps steps;
  private TestIdentity sam;
  private TestIdentity theo;
  private TestIdentity riverside;
  private Profile ollie;

  @BeforeEach
  void setUp() {
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
    theo = track(authTestSupport.createHouseholdMemberIdentity(sam));
    riverside = track(authTestSupport.createIdentity());
    ollie = steps.profileManagedBy("Ollie", sam, sam, theo);
  }

  @AfterEach
  void tearDown() {
    ServerSettingsTestSupport.resetServerSettings(dsl);
    identities.reversed().forEach(authTestSupport::deleteIdentity);
  }

  @Test
  @DisplayName(
      "Should delete read news and resolved requests when their latest event is older than the"
          + " retention period")
  void shouldDeleteReadNewsAndResolvedRequestsWhenTheirLatestEventIsOlderThanTheRetentionPeriod() {
    var share = steps.accept(riverside, steps.offer(sam, ollie, riverside));
    markRead(newsOf(sam, share));
    age(share, PAST_THE_PERIOD);

    sweeper.sweep();

    assertThat(notificationsAbout(share))
        .singleElement()
        .satisfies(
            kept -> {
              assertThat(kept.getRecipientAccountId()).isEqualTo(theo.account().getId());
              assertThat(kept.getKind()).isEqualTo(NotificationKind.PROFILE_SHARE_ANSWERED);
              assertThat(kept.getReadAt()).isEmpty();
            });
  }

  @Test
  @DisplayName(
      "Should keep read news and resolved requests when their latest event is within the retention"
          + " period")
  void shouldKeepReadNewsAndResolvedRequestsWhenTheirLatestEventIsWithinTheRetentionPeriod() {
    var share = steps.accept(riverside, steps.offer(sam, ollie, riverside));
    markRead(newsOf(sam, share));
    age(share, WITHIN_THE_PERIOD);

    sweeper.sweep();

    assertThat(notificationsAbout(share)).hasSize(3);
  }

  @Test
  @DisplayName(
      "Should keep a waiting request, read or not, when its latest event is older than the"
          + " retention period")
  void shouldKeepAWaitingRequestReadOrNotWhenItsLatestEventIsOlderThanTheRetentionPeriod() {
    var riversideAdmin =
        steps.promoted(track(authTestSupport.createHouseholdMemberIdentity(riverside)));
    var offer = steps.offer(sam, ollie, riverside);
    markRead(requestOf(riversideAdmin, offer));
    age(offer, PAST_THE_PERIOD);

    sweeper.sweep();

    assertThat(notificationsAbout(offer))
        .extracting(Notification::getRecipientAccountId)
        .containsExactlyInAnyOrder(riverside.account().getId(), riversideAdmin.account().getId());
  }

  @Test
  @DisplayName(
      "Should delete unread news that the guard hides when its latest event is older than the"
          + " retention period")
  void shouldDeleteUnreadNewsThatTheGuardHidesWhenItsLatestEventIsOlderThanTheRetentionPeriod() {
    var share = steps.accept(riverside, steps.offer(sam, ollie, riverside));
    stopManaging(theo, ollie);
    age(share, PAST_THE_PERIOD);

    sweeper.sweep();

    assertThat(notificationsAbout(share))
        .extracting(Notification::getRecipientAccountId)
        .containsExactly(sam.account().getId());
  }

  @Test
  @DisplayName(
      "Should delete every unread news that the guard hides, and keep what it shows, when there is"
          + " more of it than one batch")
  void shouldDeleteEveryHiddenUnreadNewsAndKeepTheShownWhenThereIsMoreOfItThanOneBatch() {
    var aged = new ArrayList<ProfileHouseholdShare>();
    for (var made = 0; made < 3; made++) {
      var target = track(authTestSupport.createIdentity());
      aged.add(steps.accept(target, steps.offer(sam, ollie, target)));
    }

    stopManaging(theo, ollie);
    aged.forEach(share -> age(share, PAST_THE_PERIOD));

    sweeper.sweep();

    assertThat(aged)
        .allSatisfy(
            share ->
                assertThat(notificationsAbout(share))
                    .extracting(Notification::getRecipientAccountId)
                    .containsExactly(sam.account().getId()));
  }

  @Test
  @DisplayName(
      "Should keep a disabled Account's unread news when its latest event is older than the"
          + " retention period")
  void shouldKeepADisabledAccountsUnreadNewsWhenItsLatestEventIsOlderThanTheRetentionPeriod() {
    var share = steps.accept(riverside, steps.offer(sam, ollie, riverside));
    transactionTemplate.executeWithoutResult(
        _ -> userAccountRepository.tryDisable(theo.account().getId()));
    age(share, PAST_THE_PERIOD);

    sweeper.sweep();

    transactionTemplate.executeWithoutResult(
        _ -> userAccountRepository.tryEnable(theo.account().getId()));
    assertThat(notificationService.unreadNotificationCount(authTestSupport.identityOf(theo)))
        .isOne();
  }

  @Test
  @DisplayName(
      "Should keep unread news that the guard hides when its latest event is within the retention"
          + " period")
  void shouldKeepUnreadNewsThatTheGuardHidesWhenItsLatestEventIsWithinTheRetentionPeriod() {
    var share = steps.accept(riverside, steps.offer(sam, ollie, riverside));
    stopManaging(theo, ollie);
    age(share, WITHIN_THE_PERIOD);

    sweeper.sweep();

    assertThat(notificationsAbout(share))
        .filteredOn(kept -> kept.getKind() == NotificationKind.PROFILE_SHARE_ANSWERED)
        .extracting(Notification::getRecipientAccountId)
        .containsExactlyInAnyOrder(sam.account().getId(), theo.account().getId());
  }

  @Test
  @DisplayName(
      "Should delete history past the retention period when a ServerAdmin chose a shorter one")
  void shouldDeleteHistoryPastTheRetentionPeriodWhenAServerAdminChoseAShorterOne() {
    var share = steps.accept(riverside, steps.offer(sam, ollie, riverside));
    markRead(newsOf(sam, share));
    age(share, Duration.ofDays(31));
    transactionTemplate.executeWithoutResult(
        _ ->
            dsl.update(SERVER_SETTINGS)
                .set(SERVER_SETTINGS.NOTIFICATION_RETENTION_DAYS, 30)
                .execute());

    sweeper.sweep();

    assertThat(notificationsAbout(share))
        .extracting(Notification::getRecipientAccountId)
        .containsExactly(theo.account().getId());
  }

  private void age(ProfileHouseholdShare share, Duration age) {
    var latestEvent = Instant.now().minus(age).atOffset(ZoneOffset.UTC);
    transactionTemplate.executeWithoutResult(
        _ ->
            dsl.update(SHARE_NOTIFICATION)
                .set(SHARE_NOTIFICATION.LATEST_EVENT_AT, latestEvent)
                .where(SHARE_NOTIFICATION.SHARE_ID.eq(share.getId()))
                .execute());
  }

  private void markRead(Notification notification) {
    transactionTemplate.executeWithoutResult(
        _ ->
            dsl.update(SHARE_NOTIFICATION)
                .set(SHARE_NOTIFICATION.READ_AT, Instant.now().atOffset(ZoneOffset.UTC))
                .where(SHARE_NOTIFICATION.ID.eq(notification.getId()))
                .execute());
  }

  private void stopManaging(TestIdentity manager, Profile profile) {
    transactionTemplate.executeWithoutResult(
        _ ->
            dsl.deleteFrom(PROFILE_MANAGER)
                .where(PROFILE_MANAGER.ACCOUNT_ID.eq(manager.account().getId()))
                .and(PROFILE_MANAGER.PROFILE_ID.eq(profile.getId()))
                .execute());
  }

  private Notification newsOf(TestIdentity recipient, ProfileHouseholdShare share) {
    return notificationsAbout(share).stream()
        .filter(notification -> notification.getKind() == NotificationKind.PROFILE_SHARE_ANSWERED)
        .filter(notification -> notification.getRecipientAccountId().equals(idOf(recipient)))
        .findFirst()
        .orElseThrow();
  }

  private Notification requestOf(TestIdentity recipient, ProfileHouseholdShare share) {
    return notificationsAbout(share).stream()
        .filter(notification -> notification.getKind() == NotificationKind.PROFILE_SHARE_OFFERED)
        .filter(notification -> notification.getRecipientAccountId().equals(idOf(recipient)))
        .findFirst()
        .orElseThrow();
  }

  private List<Notification> notificationsAbout(ProfileHouseholdShare share) {
    return notificationRepository.findAll().stream()
        .filter(notification -> notification.getShareId().equals(share.getId()))
        .toList();
  }

  private static UUID idOf(TestIdentity identity) {
    return identity.account().getId();
  }

  private TestIdentity track(TestIdentity identity) {
    identities.add(identity);
    return identity;
  }
}
