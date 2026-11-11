package com.streamarr.server.services.notification;

import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.notification.NotificationDraft;
import com.streamarr.server.domain.notification.NotificationKind;
import com.streamarr.server.domain.notification.ProfileShareFacts;
import com.streamarr.server.domain.notification.SubjectNotification;
import com.streamarr.server.domain.pagination.Position;
import com.streamarr.server.domain.pagination.SortDirection;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.notification.NotificationRepository;
import com.streamarr.server.services.pagination.ListPage;
import com.streamarr.server.services.pagination.PageRequest;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupport.TestIdentity;
import com.streamarr.server.support.PagedListContract;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The signed-in Account's Notifications page through the shared pagination, newest latest event
 * first. Every arranged Notification has a twin at the same time about a share the reader may not
 * see, so each page also proves that the guard's hidden Notifications neither show nor take a place
 * on a page.
 */
@Tag("IntegrationTest")
@DisplayName("Notifications paging")
class NotificationsPagedListIT
    extends PagedListContract<SubjectNotification, NotificationsPagedListIT.Scope> {

  private static final Instant NEWEST = Instant.now().truncatedTo(ChronoUnit.MICROS);

  @Autowired private NotificationService notificationService;
  @Autowired private NotificationRepository notificationRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private AuthTestSupport auth;
  @Autowired private TransactionTemplate transactionTemplate;

  /**
   * Riverside reads its Notifications: requests about Sam's offer into Riverside's Household, which
   * it administers, and about Sam's offer into Kai's Household, which it may not see.
   */
  @Builder
  record Scope(
      TestIdentity riverside,
      TestIdentity sam,
      TestIdentity kai,
      ProfileHouseholdShare shown,
      ProfileHouseholdShare hidden) {}

  @Override
  protected Scope createScope() {
    var riverside = auth.createIdentity();
    var sam = auth.createIdentity();
    var kai = auth.createIdentity();
    return Scope.builder()
        .riverside(riverside)
        .sam(sam)
        .kai(kai)
        .shown(offer(sam, riverside))
        .hidden(offer(sam, kai))
        .build();
  }

  @Override
  protected void deleteScope(Scope scope) {
    List.of(scope.riverside(), scope.sam(), scope.kai()).forEach(auth::deleteIdentity);
  }

  @Override
  protected UUID add(Scope scope, int rank) {
    var at = NEWEST.minus(Duration.ofMinutes(rank));
    insert(scope.riverside(), scope.hidden(), at);
    return insert(scope.riverside(), scope.shown(), at);
  }

  @Override
  protected void remove(Scope scope, UUID item) {
    notificationRepository.deleteById(item);
    notificationRepository.flush();
  }

  @Override
  protected ListPage<SubjectNotification> page(Scope scope, PageRequest request) {
    return notificationService.notifications(auth.identityOf(scope.riverside()), request);
  }

  @Override
  protected UUID idOf(SubjectNotification item) {
    return item.notification().getId();
  }

  @Override
  protected SortDirection tieDirection() {
    return SortDirection.DESC;
  }

  @Override
  protected boolean sortValuesCanTie() {
    return true;
  }

  @Override
  protected Optional<Position> positionBeforeArrangedItems(Scope scope) {
    return Optional.empty();
  }

  @Override
  protected Optional<Position> positionAfterArrangedItems(Scope scope) {
    return Optional.empty();
  }

  private UUID insert(TestIdentity recipient, ProfileHouseholdShare share, Instant at) {
    return transactionTemplate.execute(
        _ ->
            notificationRepository.insert(
                NotificationDraft.<NotificationKind, ProfileShareFacts>builder()
                    .recipientAccountId(recipient.account().getId())
                    .kind(NotificationKind.PROFILE_SHARE_OFFERED)
                    .subjectId(share.getId())
                    .facts(ProfileShareFacts.offered(share.getProfileId().toString()))
                    .at(at)
                    .build()));
  }

  /** The offer of the offerer's Personal Profile into the target's Household. */
  private ProfileHouseholdShare offer(TestIdentity offerer, TestIdentity target) {
    return transactionTemplate.execute(
        _ ->
            shareRepository.saveAndFlush(
                ProfileHouseholdShare.builder()
                    .profileId(offerer.profile().getId())
                    .householdId(target.household().getId())
                    .status(ProfileShareStatus.PENDING)
                    .offeredByAccountId(offerer.account().getId())
                    .expiresAt(NEWEST.plus(Duration.ofDays(7)))
                    .build()));
  }
}
