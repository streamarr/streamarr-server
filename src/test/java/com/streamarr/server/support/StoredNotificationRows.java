package com.streamarr.server.support;

import static com.streamarr.server.jooq.generated.tables.Notification.NOTIFICATION;

import com.streamarr.server.jooq.generated.tables.records.NotificationRecord;
import com.streamarr.server.services.events.notification.NotificationSnapshot;
import com.streamarr.server.services.events.notification.RecipientReference;
import com.streamarr.server.services.events.notification.SubjectReference;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The stored Notifications, read as their table holds them, for assertions on what was kept. */
@RequiredArgsConstructor
public final class StoredNotificationRows {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final DSLContext dsl;

  /** The Notifications about the subject, for every recipient, oldest first. */
  public List<Row> about(SubjectReference subject) {
    return rowsWhere(
        NOTIFICATION.SUBJECT_TYPE.eq(subject.type()).and(NOTIFICATION.SUBJECT_ID.eq(subject.id())));
  }

  /** The recipient's Notifications, oldest first. */
  public List<Row> of(RecipientReference recipient) {
    return rowsWhere(
        NOTIFICATION
            .RECIPIENT_TYPE
            .eq(recipient.type())
            .and(NOTIFICATION.RECIPIENT_ID.eq(recipient.id())));
  }

  /** Deletes the recipients' Notifications, which outlive the test's Accounts. */
  public void deleteOf(Collection<RecipientReference> recipients) {
    recipients.forEach(
        recipient ->
            dsl.deleteFrom(NOTIFICATION)
                .where(NOTIFICATION.RECIPIENT_TYPE.eq(recipient.type()))
                .and(NOTIFICATION.RECIPIENT_ID.eq(recipient.id()))
                .execute());
  }

  /** The document a snapshot is stored as, to compare with a stored row's. */
  public static JsonNode document(NotificationSnapshot snapshot) {
    return JSON.valueToTree(snapshot);
  }

  private List<Row> rowsWhere(Condition condition) {
    return dsl.selectFrom(NOTIFICATION)
        .where(condition)
        .orderBy(NOTIFICATION.FIRST_EVENT_AT, NOTIFICATION.ID)
        .fetch(StoredNotificationRows::rowOf);
  }

  private static Row rowOf(NotificationRecord stored) {
    return Row.builder()
        .id(stored.getId())
        .recipient(new RecipientReference(stored.getRecipientType(), stored.getRecipientId()))
        .kind(stored.getKind())
        .form(stored.getForm().getLiteral())
        .subject(new SubjectReference(stored.getSubjectType(), stored.getSubjectId()))
        .snapshot(JSON.readTree(stored.getSnapshot().data()))
        .createdAt(stored.getFirstEventAt().toInstant())
        .latestEventAt(stored.getLatestEventAt().toInstant())
        .readAt(instant(stored.getReadAt()))
        .resolution(Optional.ofNullable(stored.getResolution()).map(Enum::name))
        .resolvedAt(instant(stored.getResolvedAt()))
        .expiresAt(instant(stored.getExpiresAt()))
        .build();
  }

  private static Optional<Instant> instant(OffsetDateTime stored) {
    return Optional.ofNullable(stored).map(OffsetDateTime::toInstant);
  }

  /** One stored Notification. */
  @Builder
  public record Row(
      UUID id,
      RecipientReference recipient,
      String kind,
      String form,
      SubjectReference subject,
      JsonNode snapshot,
      Instant createdAt,
      Instant latestEventAt,
      Optional<Instant> readAt,
      Optional<String> resolution,
      Optional<Instant> resolvedAt,
      Optional<Instant> expiresAt) {}
}
