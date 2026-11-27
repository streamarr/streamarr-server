package com.streamarr.server.services.notification;

import com.streamarr.server.domain.notification.DismissalSelector;
import com.streamarr.server.domain.notification.RecipientReference;
import com.streamarr.server.domain.notification.StoredNotification;
import com.streamarr.server.domain.notification.SubjectReference;
import com.streamarr.server.domain.pagination.Position;
import com.streamarr.server.domain.pagination.SortDirection;
import com.streamarr.server.services.events.notification.DismissNotificationsEvent;
import com.streamarr.server.services.events.notification.ProfileShareAnswer;
import com.streamarr.server.services.events.notification.ProfileShareAnsweredSnapshot;
import com.streamarr.server.services.events.notification.SharingNotificationKinds;
import com.streamarr.server.services.events.notification.TellNotificationEvent;
import com.streamarr.server.services.pagination.ListPage;
import com.streamarr.server.services.pagination.PageRequest;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupport.TestIdentity;
import com.streamarr.server.support.PagedListContract;
import com.streamarr.server.support.StoredNotificationRows;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The signed-in Account's Notifications page through the shared pagination, newest latest event
 * first. Each arranged Notification is news about a subject of its own, told as a domain tells it,
 * and taken out of the list by a dismissal.
 */
@Tag("IntegrationTest")
@DisplayName("Notifications paging")
class NotificationsPagedListIT extends PagedListContract<StoredNotification, TestIdentity> {

  private static final Instant NEWEST = Instant.now().truncatedTo(ChronoUnit.MICROS);

  @Autowired private NotificationService notificationService;
  @Autowired private ApplicationEventPublisher eventPublisher;
  @Autowired private AuthTestSupport auth;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DSLContext dsl;

  @Override
  protected TestIdentity createScope() {
    return auth.createIdentity();
  }

  @Override
  protected void deleteScope(TestIdentity reader) {
    new StoredNotificationRows(dsl).deleteOf(List.of(accountOf(reader)));
    auth.deleteIdentity(reader);
  }

  @Override
  protected UUID add(TestIdentity reader, int rank) {
    var subject = SubjectReference.profileShare(UUID.randomUUID());
    publish(
        TellNotificationEvent.builder()
            .kind(SharingNotificationKinds.PROFILE_SHARE_ANSWERED)
            .subject(subject)
            .recipients(Set.of(accountOf(reader)))
            .snapshot(new ProfileShareAnsweredSnapshot("Kai", ProfileShareAnswer.ACCEPTED))
            .at(NEWEST.minus(Duration.ofMinutes(rank)))
            .build());
    return new StoredNotificationRows(dsl).about(subject).getFirst().id();
  }

  @Override
  protected void remove(TestIdentity reader, UUID item) {
    publish(new DismissNotificationsEvent(DismissalSelector.byId(Set.of(item)).build()));
  }

  @Override
  protected ListPage<StoredNotification> page(TestIdentity reader, PageRequest request) {
    return notificationService.notifications(auth.identityOf(reader), request);
  }

  @Override
  protected UUID idOf(StoredNotification item) {
    return item.id();
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
  protected Optional<Position> positionBeforeArrangedItems(TestIdentity reader) {
    return Optional.empty();
  }

  @Override
  protected Optional<Position> positionAfterArrangedItems(TestIdentity reader) {
    return Optional.empty();
  }

  private void publish(Object event) {
    transactionTemplate.executeWithoutResult(_ -> eventPublisher.publishEvent(event));
  }

  private static RecipientReference accountOf(TestIdentity reader) {
    return RecipientReference.account(reader.account().getId());
  }
}
