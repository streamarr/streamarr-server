package com.streamarr.server.services.notification;

import static com.streamarr.server.jooq.generated.tables.Notification.NOTIFICATION;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.notification.RecipientReference;
import com.streamarr.server.domain.notification.RequestResolution;
import com.streamarr.server.domain.notification.SubjectReference;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.events.notification.ProfileShareAnswer;
import com.streamarr.server.services.events.notification.ProfileShareAnsweredSnapshot;
import com.streamarr.server.services.events.notification.ProfileShareOfferedSnapshot;
import com.streamarr.server.services.events.notification.ResolveRequestsEvent;
import com.streamarr.server.services.events.notification.SharingNotificationKinds;
import com.streamarr.server.services.events.notification.TellNotificationEvent;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.serversettings.ServerSettingsService;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupport.TestIdentity;
import com.streamarr.server.support.ServerSettingsTestSupport;
import com.streamarr.server.support.StoredNotificationRows;
import com.streamarr.server.support.StoredNotificationRows.Row;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Retention (ADR 0063 §Retention): history, the news that its recipient read and the requests that
 * resolved, a request that reads as expired from its stored expiry included, goes once its latest
 * event is older than the retention period, 90 days unless a ServerAdmin chose another. Unread news
 * and waiting requests stay whatever their age. The recipients and subjects are references the
 * sweep never looks up, so the tests name made-up ids, apart from the one Account that is disabled.
 */
@Tag("IntegrationTest")
@DisplayName("Notification Retention Sweeper Integration Tests")
class NotificationRetentionSweeperIT extends AbstractIntegrationTest {

  private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);
  private static final Instant PAST_THE_PERIOD = NOW.minus(Duration.ofDays(91));
  private static final Instant WITHIN_THE_PERIOD = NOW.minus(Duration.ofDays(89));
  private static final Duration OFFER_TIME = Duration.ofDays(7);

  @Autowired private NotificationRetentionSweeper sweeper;
  @Autowired private ServerSettingsService serverSettingsService;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private ApplicationEventPublisher eventPublisher;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DSLContext dsl;

  private final RecipientReference sam = RecipientReference.account(UUID.randomUUID());
  private final RecipientReference kai = RecipientReference.account(UUID.randomUUID());
  private final List<TestIdentity> identities = new ArrayList<>();

  private StoredNotificationRows rows;

  @BeforeEach
  void setUp() {
    rows = new StoredNotificationRows(dsl);
  }

  @AfterEach
  void tearDown() {
    ServerSettingsTestSupport.resetServerSettings(dsl);
    var recipients = new ArrayList<>(List.of(sam, kai));
    identities.stream().map(NotificationRetentionSweeperIT::accountOf).forEach(recipients::add);
    rows.deleteOf(recipients);
    identities.reversed().forEach(authTestSupport::deleteIdentity);
  }

  @Test
  @DisplayName(
      "Should delete read news and resolved requests when their latest event is older than the"
          + " retention period")
  void shouldDeleteReadNewsAndResolvedRequestsWhenTheirLatestEventIsOlderThanTheRetentionPeriod() {
    var share = aShare();
    tell(answered(share, PAST_THE_PERIOD).recipients(Set.of(sam, kai)).build());
    markRead(sam);
    var decided = aShare();
    tell(offered(decided, PAST_THE_PERIOD).build());
    publish(decided(decided, PAST_THE_PERIOD.plus(Duration.ofDays(1))));
    var withdrawn = aShare();
    tell(offered(withdrawn, PAST_THE_PERIOD).build());
    publish(withdrawn(withdrawn, PAST_THE_PERIOD.plus(Duration.ofDays(1))));

    sweeper.sweep();

    assertThat(rows.of(sam)).isEmpty();
    assertThat(rows.of(kai)).extracting(Row::subject).containsExactly(share);
  }

  @Test
  @DisplayName(
      "Should delete a request that reads as expired from its expiry when its latest event is older"
          + " than the retention period")
  void
      shouldDeleteARequestThatReadsAsExpiredFromItsExpiryWhenItsLatestEventIsOlderThanTheRetentionPeriod() {
    tell(offered(aShare(), PAST_THE_PERIOD).build());

    sweeper.sweep();

    assertThat(rows.of(sam)).isEmpty();
  }

  @Test
  @DisplayName(
      "Should keep unread news and waiting requests, read or not, when their latest event is older"
          + " than the retention period")
  void
      shouldKeepUnreadNewsAndWaitingRequestsReadOrNotWhenTheirLatestEventIsOlderThanTheRetentionPeriod() {
    var news = aShare();
    tell(answered(news, NOW.minus(Duration.ofDays(365))).build());
    var waiting = aShare();
    tell(
        offered(waiting, PAST_THE_PERIOD)
            .subjectExpiresAt(Optional.of(NOW.plus(OFFER_TIME)))
            .build());
    var readWaiting = aShare();
    tell(
        offered(readWaiting, PAST_THE_PERIOD)
            .recipients(Set.of(kai))
            .subjectExpiresAt(Optional.of(NOW.plus(OFFER_TIME)))
            .build());
    markRead(kai);

    sweeper.sweep();

    assertThat(rows.of(sam)).extracting(Row::subject).containsExactlyInAnyOrder(news, waiting);
    assertThat(rows.of(kai)).extracting(Row::subject).containsExactly(readWaiting);
  }

  @Test
  @DisplayName(
      "Should keep a disabled Account's unread news when its latest event is older than the"
          + " retention period")
  void shouldKeepADisabledAccountsUnreadNewsWhenItsLatestEventIsOlderThanTheRetentionPeriod() {
    var theo = track(authTestSupport.createIdentity());
    var share = aShare();
    tell(answered(share, PAST_THE_PERIOD).recipients(Set.of(accountOf(theo))).build());
    transactionTemplate.executeWithoutResult(
        _ -> assertThat(userAccountRepository.tryDisable(theo.account().getId())).isTrue());

    sweeper.sweep();

    assertThat(rows.of(accountOf(theo))).extracting(Row::subject).containsExactly(share);
  }

  @Test
  @DisplayName(
      "Should keep a disabled Account's waiting request when its latest event is older than the"
          + " retention period")
  void shouldKeepADisabledAccountsWaitingRequestWhenItsLatestEventIsOlderThanTheRetentionPeriod() {
    var theo = track(authTestSupport.createIdentity());
    var offer = aShare();
    tell(
        offered(offer, PAST_THE_PERIOD)
            .recipients(Set.of(accountOf(theo)))
            .subjectExpiresAt(Optional.of(NOW.plus(OFFER_TIME)))
            .build());
    transactionTemplate.executeWithoutResult(
        _ -> assertThat(userAccountRepository.tryDisable(theo.account().getId())).isTrue());

    sweeper.sweep();

    assertThat(rows.of(accountOf(theo))).extracting(Row::subject).containsExactly(offer);
  }

  @Test
  @DisplayName("Should keep history when its latest event is within the retention period")
  void shouldKeepHistoryWhenItsLatestEventIsWithinTheRetentionPeriod() {
    var news = aShare();
    tell(answered(news, WITHIN_THE_PERIOD).build());
    markRead(sam);
    var decided = aShare();
    tell(offered(decided, WITHIN_THE_PERIOD).build());
    publish(decided(decided, WITHIN_THE_PERIOD.plus(Duration.ofDays(1))));
    var expired = aShare();
    tell(offered(expired, WITHIN_THE_PERIOD).build());

    sweeper.sweep();

    assertThat(rows.of(sam))
        .extracting(Row::subject)
        .containsExactlyInAnyOrder(news, decided, expired);
  }

  @Test
  @DisplayName(
      "Should delete history past the retention period when a ServerAdmin chose a shorter one")
  void shouldDeleteHistoryPastTheRetentionPeriodWhenAServerAdminChoseAShorterOne() {
    var serverAdmin = track(authTestSupport.createAdminIdentity());
    assertThat(
            serverSettingsService.changeNotificationRetention(
                authTestSupport.freshIdentityOf(serverAdmin), 30))
        .isInstanceOf(Outcome.Accepted.class);
    var older = aShare();
    tell(answered(older, NOW.minus(Duration.ofDays(31))).build());
    var newer = aShare();
    tell(answered(newer, NOW.minus(Duration.ofDays(29))).build());
    markRead(sam);

    sweeper.sweep();

    assertThat(rows.of(sam)).extracting(Row::subject).containsExactly(newer);
  }

  @Test
  @DisplayName(
      "Should delete every aged history and keep the rest when there is more of it than one batch")
  void shouldDeleteEveryAgedHistoryAndKeepTheRestWhenThereIsMoreOfItThanOneBatch() {
    IntStream.range(0, 5).forEach(_ -> tell(answered(aShare(), PAST_THE_PERIOD).build()));
    markRead(sam);
    var unread = aShare();
    tell(answered(unread, PAST_THE_PERIOD).build());

    sweeper.sweep();

    assertThat(rows.of(sam)).extracting(Row::subject).containsExactly(unread);
  }

  private void tell(TellNotificationEvent tell) {
    publish(tell);
  }

  private void publish(Object event) {
    transactionTemplate.executeWithoutResult(_ -> eventPublisher.publishEvent(event));
  }

  /** Marks every Notification of the recipient read, as its reader would. */
  private void markRead(RecipientReference recipient) {
    transactionTemplate.executeWithoutResult(
        _ ->
            dsl.update(NOTIFICATION)
                .set(NOTIFICATION.READ_AT, NOW.atOffset(ZoneOffset.UTC))
                .where(NOTIFICATION.RECIPIENT_TYPE.eq(recipient.type()))
                .and(NOTIFICATION.RECIPIENT_ID.eq(recipient.id()))
                .execute());
  }

  private static SubjectReference aShare() {
    return SubjectReference.profileShare(UUID.randomUUID());
  }

  /** News that the share was answered, told to Sam at the instant. */
  private TellNotificationEvent.TellNotificationEventBuilder answered(
      SubjectReference share, Instant at) {
    return TellNotificationEvent.builder()
        .kind(SharingNotificationKinds.PROFILE_SHARE_ANSWERED)
        .subject(share)
        .recipients(Set.of(sam))
        .snapshot(new ProfileShareAnsweredSnapshot("Ollie", ProfileShareAnswer.ACCEPTED))
        .at(at);
  }

  /** A request about the offer, told to Sam at the instant, which waits for a week from then. */
  private TellNotificationEvent.TellNotificationEventBuilder offered(
      SubjectReference offer, Instant at) {
    return TellNotificationEvent.builder()
        .kind(SharingNotificationKinds.PROFILE_SHARE_OFFERED)
        .subject(offer)
        .recipients(Set.of(sam))
        .snapshot(new ProfileShareOfferedSnapshot("Ollie", Optional.empty()))
        .at(at)
        .subjectExpiresAt(Optional.of(at.plus(OFFER_TIME)));
  }

  private static ResolveRequestsEvent decided(SubjectReference offer, Instant at) {
    return ResolveRequestsEvent.builder()
        .kind(SharingNotificationKinds.PROFILE_SHARE_OFFERED)
        .subject(offer)
        .resolution(RequestResolution.DECIDED)
        .answer(Optional.of(ProfileShareAnswer.ACCEPTED))
        .at(at)
        .build();
  }

  private static ResolveRequestsEvent withdrawn(SubjectReference offer, Instant at) {
    return ResolveRequestsEvent.builder()
        .kind(SharingNotificationKinds.PROFILE_SHARE_OFFERED)
        .subject(offer)
        .resolution(RequestResolution.WITHDRAWN)
        .at(at)
        .build();
  }

  private TestIdentity track(TestIdentity identity) {
    identities.add(identity);
    return identity;
  }

  private static RecipientReference accountOf(TestIdentity identity) {
    return RecipientReference.account(identity.account().getId());
  }
}
