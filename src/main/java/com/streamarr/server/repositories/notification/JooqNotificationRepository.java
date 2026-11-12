package com.streamarr.server.repositories.notification;

import static com.streamarr.server.jooq.generated.tables.Notification.NOTIFICATION;
import static org.jooq.impl.DSL.cast;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.function;
import static org.jooq.impl.DSL.greatest;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.jsonbGetAttributeAsText;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.row;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.val;
import static org.jooq.impl.DSL.when;

import com.streamarr.server.domain.pagination.Row;
import com.streamarr.server.domain.pagination.Seek;
import com.streamarr.server.jooq.generated.enums.NotificationForm;
import com.streamarr.server.jooq.generated.enums.RequestResolution;
import com.streamarr.server.jooq.generated.tables.records.NotificationRecord;
import com.streamarr.server.repositories.Keyset;
import com.streamarr.server.services.events.notification.DismissalSelector;
import com.streamarr.server.services.events.notification.NewsUpdate;
import com.streamarr.server.services.events.notification.NotificationKind;
import com.streamarr.server.services.events.notification.RecipientReference;
import com.streamarr.server.services.events.notification.ResolveRequestsEvent;
import com.streamarr.server.services.events.notification.SubjectReference;
import com.streamarr.server.services.events.notification.TellNotificationEvent;
import com.streamarr.server.services.notification.NotificationKindCatalog;
import com.streamarr.server.services.notification.StoredNotification;
import com.streamarr.server.services.notification.events.ChangedNotification;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.InsertValuesStep9;
import org.jooq.JSONB;
import org.jooq.Record3;
import org.jooq.impl.SQLDataType;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JooqNotificationRepository implements NotificationRepository {

  // The unique indexes over each recipient's Notifications of a kind about a subject: one unread
  // news, and one request.
  private static final List<Field<?>> RECIPIENT_KIND_AND_SUBJECT =
      List.of(
          NOTIFICATION.RECIPIENT_TYPE,
          NOTIFICATION.RECIPIENT_ID,
          NOTIFICATION.KIND,
          NOTIFICATION.SUBJECT_TYPE,
          NOTIFICATION.SUBJECT_ID);

  private static final Keyset<OffsetDateTime> NEWEST_FIRST =
      Keyset.<OffsetDateTime>builder()
          .sortValue(NOTIFICATION.LATEST_EVENT_AT)
          .itemId(NOTIFICATION.ID)
          .build();

  private final DSLContext dsl;
  private final NotificationKindCatalog catalog;

  @Override
  public List<ChangedNotification> tellNews(TellNotificationEvent tell) {
    var laterOrSame = excluded(NOTIFICATION.LATEST_EVENT_AT).ge(NOTIFICATION.LATEST_EVENT_AT);
    return rowsFor(tell)
        .onConflict(RECIPIENT_KIND_AND_SUBJECT)
        .where(
            NOTIFICATION.FORM.eq(inline(NotificationForm.NEWS)).and(NOTIFICATION.READ_AT.isNull()))
        .doUpdate()
        .set(NOTIFICATION.SNAPSHOT, updatedSnapshot(tell.kind().newsUpdate(), laterOrSame))
        .set(
            NOTIFICATION.LATEST_EVENT_AT,
            greatest(NOTIFICATION.LATEST_EVENT_AT, excluded(NOTIFICATION.LATEST_EVENT_AT)))
        .returningResult(NOTIFICATION.ID, NOTIFICATION.RECIPIENT_TYPE, NOTIFICATION.RECIPIENT_ID)
        .fetch(JooqNotificationRepository::changed);
  }

  @Override
  public List<ChangedNotification> tellRequests(TellNotificationEvent tell) {
    return rowsFor(tell)
        .onConflict(RECIPIENT_KIND_AND_SUBJECT)
        .where(NOTIFICATION.FORM.eq(inline(NotificationForm.REQUEST)))
        .doNothing()
        .returningResult(NOTIFICATION.ID, NOTIFICATION.RECIPIENT_TYPE, NOTIFICATION.RECIPIENT_ID)
        .fetch(JooqNotificationRepository::changed);
  }

  @Override
  public List<ChangedNotification> resolve(ResolveRequestsEvent resolution) {
    var at = val(resolution.at().atOffset(ZoneOffset.UTC));
    var beforeTheExpiry = NOTIFICATION.EXPIRES_AT.isNull().or(at.lt(NOTIFICATION.EXPIRES_AT));
    var resolved = RequestResolution.valueOf(resolution.resolution().name());
    return dsl.update(NOTIFICATION)
        .set(
            NOTIFICATION.RESOLUTION,
            when(beforeTheExpiry, inline(resolved)).otherwise(inline(RequestResolution.EXPIRED)))
        .set(NOTIFICATION.RESOLVED_AT, when(beforeTheExpiry, at).otherwise(NOTIFICATION.EXPIRES_AT))
        .set(NOTIFICATION.SNAPSHOT, snapshotAnswering(resolution, beforeTheExpiry))
        .where(NOTIFICATION.KIND.eq(resolution.kind().name()))
        .and(NOTIFICATION.SUBJECT_TYPE.eq(resolution.subject().type()))
        .and(NOTIFICATION.SUBJECT_ID.eq(resolution.subject().id()))
        .and(NOTIFICATION.FORM.eq(inline(NotificationForm.REQUEST)))
        .and(NOTIFICATION.RESOLUTION.isNull())
        .returningResult(NOTIFICATION.ID, NOTIFICATION.RECIPIENT_TYPE, NOTIFICATION.RECIPIENT_ID)
        .fetch(JooqNotificationRepository::changed);
  }

  @Override
  public List<ChangedNotification> dismiss(DismissalSelector selector) {
    return dsl.deleteFrom(NOTIFICATION)
        .where(selected(selector.targets()))
        .and(
            selector.recipient().map(JooqNotificationRepository::ofRecipient).orElse(noCondition()))
        .and(ofKinds(selector.kinds()))
        .and(selector.form().map(JooqNotificationRepository::ofForm).orElse(noCondition()))
        .returningResult(NOTIFICATION.ID, NOTIFICATION.RECIPIENT_TYPE, NOTIFICATION.RECIPIENT_ID)
        .fetch(JooqNotificationRepository::changed);
  }

  @Override
  public void deleteOfRecipient(RecipientReference recipient) {
    dsl.deleteFrom(NOTIFICATION).where(ofRecipient(recipient)).execute();
  }

  @Override
  public int deleteHistoryBefore(Instant cutoff, Instant now, int limit) {
    var agedHistory =
        NOTIFICATION.LATEST_EVENT_AT.lt(cutoff.atOffset(ZoneOffset.UTC)).and(historyAt(now));
    return dsl.deleteFrom(NOTIFICATION)
        .where(agedHistory)
        .and(
            NOTIFICATION.ID.in(
                select(NOTIFICATION.ID)
                    .from(NOTIFICATION)
                    .where(agedHistory)
                    .orderBy(NOTIFICATION.ID)
                    .limit(limit)))
        .execute();
  }

  @Override
  public List<Row<StoredNotification>> rowsOfRecipient(RecipientReference recipient, Seek seek) {
    return NEWEST_FIRST
        .rowsBeyond(dsl.selectFrom(NOTIFICATION), ofRecipient(recipient), seek)
        .fetch(this::rowOf);
  }

  @Override
  public List<Row<StoredNotification>> rowsOfRecipientWithIds(
      RecipientReference recipient, Collection<UUID> notificationIds) {
    if (notificationIds.isEmpty()) {
      return List.of();
    }

    return dsl.selectFrom(NOTIFICATION)
        .where(ofRecipient(recipient))
        .and(NOTIFICATION.ID.in(notificationIds))
        .fetch(this::rowOf);
  }

  @Override
  public int countUnread(RecipientReference recipient, Instant now) {
    return dsl.fetchCount(NOTIFICATION, ofRecipient(recipient).and(unreadAt(now)));
  }

  @Override
  public List<ChangedNotification> markRead(
      RecipientReference recipient, Collection<UUID> notificationIds, Instant now) {
    if (notificationIds.isEmpty()) {
      return List.of();
    }

    return dsl.update(NOTIFICATION)
        .set(NOTIFICATION.READ_AT, now.atOffset(ZoneOffset.UTC))
        .where(ofRecipient(recipient))
        .and(NOTIFICATION.ID.in(notificationIds))
        .and(unreadAt(now))
        .returningResult(NOTIFICATION.ID, NOTIFICATION.RECIPIENT_TYPE, NOTIFICATION.RECIPIENT_ID)
        .fetch(JooqNotificationRepository::changed);
  }

  // Unread at the instant: not marked read, not resolved by an event, and, for a request, before
  // its expiry. News has no expiry.
  private static Condition unreadAt(Instant now) {
    return NOTIFICATION
        .READ_AT
        .isNull()
        .and(NOTIFICATION.RESOLUTION.isNull())
        .and(
            NOTIFICATION
                .EXPIRES_AT
                .isNull()
                .or(NOTIFICATION.EXPIRES_AT.gt(now.atOffset(ZoneOffset.UTC))));
  }

  // History at the instant: news that its recipient read, and a request that an event resolved or
  // whose expiry has passed. History never stops being history.
  private static Condition historyAt(Instant now) {
    var readNews =
        NOTIFICATION.FORM.eq(inline(NotificationForm.NEWS)).and(NOTIFICATION.READ_AT.isNotNull());
    var settledRequest =
        NOTIFICATION
            .FORM
            .eq(inline(NotificationForm.REQUEST))
            .and(
                NOTIFICATION
                    .RESOLUTION
                    .isNotNull()
                    .or(NOTIFICATION.EXPIRES_AT.le(now.atOffset(ZoneOffset.UTC))));
    return readNews.or(settledRequest);
  }

  private Row<StoredNotification> rowOf(NotificationRecord stored) {
    return new Row<>(storedOf(stored), NEWEST_FIRST.rowOf(stored).position());
  }

  private StoredNotification storedOf(NotificationRecord stored) {
    var kind =
        catalog
            .kindNamed(stored.getKind())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "No Notification kind is named %s".formatted(stored.getKind())));
    return StoredNotification.builder()
        .id(stored.getId())
        .recipient(new RecipientReference(stored.getRecipientType(), stored.getRecipientId()))
        .kind(kind)
        .subject(new SubjectReference(stored.getSubjectType(), stored.getSubjectId()))
        .snapshot(SnapshotJson.read(kind, stored.getSnapshot()))
        .createdAt(stored.getCreatedOn().toInstant())
        .latestEventAt(stored.getLatestEventAt().toInstant())
        .readAt(instantOf(stored.getReadAt()))
        .resolution(
            Optional.ofNullable(stored.getResolution())
                .map(JooqNotificationRepository::domainResolutionOf))
        .resolvedAt(instantOf(stored.getResolvedAt()))
        .expiresAt(instantOf(stored.getExpiresAt()))
        .build();
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.server.domain.notification.RequestResolution domainResolutionOf(
      RequestResolution stored) {
    return com.streamarr.server.domain.notification.RequestResolution.valueOf(stored.name());
  }

  private static Optional<Instant> instantOf(OffsetDateTime stored) {
    return Optional.ofNullable(stored).map(OffsetDateTime::toInstant);
  }

  private static Condition selected(DismissalSelector.Targets targets) {
    return switch (targets) {
      case DismissalSelector.ById(var notificationIds) -> NOTIFICATION.ID.in(notificationIds);
      case DismissalSelector.BySubject(var subjects) ->
          row(NOTIFICATION.SUBJECT_TYPE, NOTIFICATION.SUBJECT_ID)
              .in(subjects.stream().map(subject -> row(subject.type(), subject.id())).toList());
    };
  }

  private static Condition ofRecipient(RecipientReference recipient) {
    return NOTIFICATION
        .RECIPIENT_TYPE
        .eq(recipient.type())
        .and(NOTIFICATION.RECIPIENT_ID.eq(recipient.id()));
  }

  // No kind named narrows to none: every kind.
  private static Condition ofKinds(Set<NotificationKind> kinds) {
    if (kinds.isEmpty()) {
      return noCondition();
    }

    return NOTIFICATION.KIND.in(kinds.stream().map(NotificationKind::name).toList());
  }

  private static Condition ofForm(DismissalSelector.DismissedForm form) {
    return switch (form) {
      case NEWS -> NOTIFICATION.FORM.eq(inline(NotificationForm.NEWS));
      case WAITING_REQUESTS ->
          NOTIFICATION
              .FORM
              .eq(inline(NotificationForm.REQUEST))
              .and(NOTIFICATION.RESOLUTION.isNull());
    };
  }

  // A decision made before the expiry joins its answer to the snapshot, under the name every
  // request kind's snapshot gives it.
  private static Field<JSONB> snapshotAnswering(
      ResolveRequestsEvent resolution, Condition beforeTheExpiry) {
    return resolution
        .answer()
        .map(
            answer ->
                when(beforeTheExpiry, withAnswer(answer.name())).otherwise(NOTIFICATION.SNAPSHOT))
        .orElse(NOTIFICATION.SNAPSHOT);
  }

  private static Field<JSONB> withAnswer(String answer) {
    return function(
        "jsonb_set",
        SQLDataType.JSONB,
        NOTIFICATION.SNAPSHOT,
        val(new String[] {SnapshotJson.ANSWER}, SQLDataType.CLOB.array()),
        function("to_jsonb", SQLDataType.JSONB, cast(val(answer), SQLDataType.CLOB)));
  }

  // The later event's snapshot, or the stored one when the arriving event is the earlier; a kind
  // that adds figures then adds the arriving event's figures to the stored ones, either way.
  private static Field<JSONB> updatedSnapshot(NewsUpdate update, Condition laterOrSame) {
    var later = when(laterOrSame, excluded(NOTIFICATION.SNAPSHOT)).otherwise(NOTIFICATION.SNAPSHOT);
    return switch (update) {
      case NewsUpdate.Replace _ -> later;
      case NewsUpdate.AddFigures(var figures) -> withFiguresAdded(later, figures);
    };
  }

  private static Field<JSONB> withFiguresAdded(Field<JSONB> snapshot, Set<String> figures) {
    var added = snapshot;
    for (var figure : figures.stream().sorted().toList()) {
      added = withFigureAdded(added, figure);
    }

    return added;
  }

  private static Field<JSONB> withFigureAdded(Field<JSONB> snapshot, String figure) {
    var sum =
        figureOf(NOTIFICATION.SNAPSHOT, figure)
            .plus(figureOf(excluded(NOTIFICATION.SNAPSHOT), figure));
    return function(
        "jsonb_set",
        SQLDataType.JSONB,
        snapshot,
        val(new String[] {figure}, SQLDataType.CLOB.array()),
        function("to_jsonb", SQLDataType.JSONB, sum));
  }

  // A figure that a snapshot lacks counts as zero.
  private static Field<Long> figureOf(Field<JSONB> snapshot, String figure) {
    return coalesce(
        cast(jsonbGetAttributeAsText(snapshot, figure), SQLDataType.BIGINT), inline(0L));
  }

  private InsertValuesStep9<
          NotificationRecord,
          String,
          UUID,
          String,
          NotificationForm,
          String,
          UUID,
          JSONB,
          OffsetDateTime,
          OffsetDateTime>
      rowsFor(TellNotificationEvent tell) {
    var insert =
        dsl.insertInto(
            NOTIFICATION,
            NOTIFICATION.RECIPIENT_TYPE,
            NOTIFICATION.RECIPIENT_ID,
            NOTIFICATION.KIND,
            NOTIFICATION.FORM,
            NOTIFICATION.SUBJECT_TYPE,
            NOTIFICATION.SUBJECT_ID,
            NOTIFICATION.SNAPSHOT,
            NOTIFICATION.LATEST_EVENT_AT,
            NOTIFICATION.EXPIRES_AT);
    var kind = tell.kind();
    var form = NotificationForm.valueOf(kind.form().name());
    var snapshot = SnapshotJson.write(tell.snapshot());
    var at = tell.at().atOffset(ZoneOffset.UTC);
    var expiresAt = tell.subjectExpiresAt().map(expiry -> expiry.atOffset(ZoneOffset.UTC));
    for (var recipient : tell.recipients()) {
      insert =
          insert.values(
              recipient.type(),
              recipient.id(),
              kind.name(),
              form,
              tell.subject().type(),
              tell.subject().id(),
              snapshot,
              at,
              expiresAt.orElse(null));
    }

    return insert;
  }

  private static ChangedNotification changed(Record3<UUID, String, UUID> row) {
    return new ChangedNotification(
        row.value1(), new RecipientReference(row.value2(), row.value3()));
  }
}
