package com.streamarr.server.services.notification;

import com.streamarr.server.domain.notification.Notification;
import com.streamarr.server.domain.notification.SubjectNotification;
import com.streamarr.server.domain.pagination.ListOrder;
import com.streamarr.server.domain.pagination.Position;
import com.streamarr.server.domain.pagination.Row;
import com.streamarr.server.domain.pagination.Seek;
import com.streamarr.server.domain.pagination.SortDirection;
import com.streamarr.server.domain.pagination.SortValueKind;
import com.streamarr.server.repositories.notification.ShareNotificationRepository;
import com.streamarr.server.services.pagination.PagedList;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * One Account's Notifications that the guard shows it, newest latest event first (ADR 0063 §The
 * list's order and paging). The rows come from SQL in list order; a Notification the guard hides
 * takes no place on a page, so a read that the guard thinned continues from its last row until the
 * page is full or the list ends.
 */
@Component
@RequiredArgsConstructor
public class NotificationsList implements PagedList<SubjectNotification, UUID> {

  public static final ListOrder NEWEST_FIRST =
      ListOrder.builder().valueKind(SortValueKind.INSTANT).direction(SortDirection.DESC).build();

  private final ShareNotificationRepository notificationRepository;
  private final NotificationReads notificationReads;

  @Override
  public ListOrder order(UUID recipientAccountId) {
    return NEWEST_FIRST;
  }

  @Override
  public List<Row<SubjectNotification>> rowsBeyond(UUID recipientAccountId, Seek seek) {
    var shown = new ArrayList<Row<SubjectNotification>>();
    var read = seek;
    while (true) {
      var rows = notificationRepository.rowsOfRecipient(recipientAccountId, read);
      shownOf(rows).stream().limit((long) seek.limit() - shown.size()).forEach(shown::add);
      if (rows.size() < read.limit() || shown.size() == seek.limit()) {
        return shown;
      }

      read = continuing(read, rows.getLast());
    }
  }

  /** The rows that the guard shows their recipients, in the order read. */
  List<Row<SubjectNotification>> shownOf(List<Row<Notification>> rows) {
    Map<UUID, Position> positions =
        rows.stream().collect(Collectors.toMap(row -> row.item().getId(), Row::position));
    return notificationReads.shown(rows.stream().map(Row::item).toList()).stream()
        .map(shown -> new Row<>(shown, positions.get(shown.notification().getId())))
        .toList();
  }

  private static Seek continuing(Seek read, Row<Notification> last) {
    return Seek.builder()
        .order(read.order())
        .position(Optional.of(last.position()))
        .direction(read.direction())
        .limit(read.limit())
        .includingPosition(false)
        .build();
  }
}
