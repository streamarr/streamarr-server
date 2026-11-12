package com.streamarr.server.repositories.notification;

import static com.streamarr.server.jooq.generated.tables.Notification.NOTIFICATION;
import static org.jooq.impl.DSL.cast;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.function;
import static org.jooq.impl.DSL.greatest;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.val;
import static org.jooq.impl.DSL.when;

import com.streamarr.server.jooq.generated.enums.NotificationForm;
import com.streamarr.server.jooq.generated.enums.RequestResolution;
import com.streamarr.server.jooq.generated.tables.records.NotificationRecord;
import com.streamarr.server.services.events.notification.RecipientReference;
import com.streamarr.server.services.events.notification.ResolveRequestsEvent;
import com.streamarr.server.services.events.notification.TellNotificationEvent;
import com.streamarr.server.services.notification.events.ChangedNotification;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
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

  private final DSLContext dsl;

  @Override
  public List<ChangedNotification> tellNews(TellNotificationEvent tell) {
    var laterOrSame = excluded(NOTIFICATION.LATEST_EVENT_AT).ge(NOTIFICATION.LATEST_EVENT_AT);
    return rowsFor(tell)
        .onConflict(RECIPIENT_KIND_AND_SUBJECT)
        .where(
            NOTIFICATION.FORM.eq(inline(NotificationForm.NEWS)).and(NOTIFICATION.READ_AT.isNull()))
        .doUpdate()
        .set(
            NOTIFICATION.SNAPSHOT,
            when(laterOrSame, excluded(NOTIFICATION.SNAPSHOT)).otherwise(NOTIFICATION.SNAPSHOT))
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
