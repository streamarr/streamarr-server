package com.streamarr.server.repositories.notification;

import static com.streamarr.server.jooq.generated.tables.Notification.NOTIFICATION;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.greatest;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.when;

import com.streamarr.server.jooq.generated.enums.NotificationForm;
import com.streamarr.server.jooq.generated.tables.records.NotificationRecord;
import com.streamarr.server.services.events.notification.RecipientReference;
import com.streamarr.server.services.events.notification.TellNotificationEvent;
import com.streamarr.server.services.notification.events.ChangedNotification;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.InsertValuesStep8;
import org.jooq.JSONB;
import org.jooq.Record3;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JooqNotificationRepository implements NotificationRepository {

  private final DSLContext dsl;

  @Override
  public List<ChangedNotification> tellNews(TellNotificationEvent tell) {
    var laterOrSame = excluded(NOTIFICATION.LATEST_EVENT_AT).ge(NOTIFICATION.LATEST_EVENT_AT);
    return rowsFor(tell)
        .onConflict(
            NOTIFICATION.RECIPIENT_TYPE,
            NOTIFICATION.RECIPIENT_ID,
            NOTIFICATION.KIND,
            NOTIFICATION.SUBJECT_TYPE,
            NOTIFICATION.SUBJECT_ID)
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

  private InsertValuesStep8<
          NotificationRecord,
          String,
          UUID,
          String,
          NotificationForm,
          String,
          UUID,
          JSONB,
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
            NOTIFICATION.LATEST_EVENT_AT);
    var kind = tell.kind();
    var form = NotificationForm.valueOf(kind.form().name());
    var snapshot = SnapshotJson.write(tell.snapshot());
    var at = tell.at().atOffset(ZoneOffset.UTC);
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
              at);
    }

    return insert;
  }

  private static ChangedNotification changed(Record3<UUID, String, UUID> row) {
    return new ChangedNotification(
        row.value1(), new RecipientReference(row.value2(), row.value3()));
  }
}
