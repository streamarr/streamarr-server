package com.streamarr.server.repositories.notification;

import static com.streamarr.server.jooq.generated.tables.Notification.NOTIFICATION;
import static org.jooq.impl.DSL.greatest;
import static org.jooq.impl.DSL.inline;

import com.streamarr.server.domain.notification.NotificationDraft;
import com.streamarr.server.domain.notification.NotificationKind;
import com.streamarr.server.domain.notification.ProfileShareFacts;
import com.streamarr.server.domain.notification.UnreadNews;
import com.streamarr.server.jooq.generated.enums.NotificationForm;
import com.streamarr.server.jooq.generated.enums.ProfileShareAnswer;
import com.streamarr.server.jooq.generated.enums.ProfileShareEndedBy;
import com.streamarr.server.jooq.generated.tables.records.NotificationRecord;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.InsertSetMoreStep;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

@RequiredArgsConstructor
public class NotificationRepositoryCustomImpl implements NotificationRepositoryCustom {

  // PostgreSQL leaves xmax at zero on a row the statement inserted, and sets it on a row the
  // statement's ON CONFLICT DO UPDATE took.
  private static final Field<Boolean> INSERTED =
      DSL.field("(xmax = 0)", SQLDataType.BOOLEAN).as("inserted");

  private final DSLContext dsl;

  @Override
  public UUID insert(NotificationDraft<NotificationKind, ProfileShareFacts> draft) {
    return values(draft).returning(NOTIFICATION.ID).fetchSingle().getId();
  }

  @Override
  public UnreadNews<ProfileShareFacts> upsertUnreadNews(
      NotificationDraft<NotificationKind, ProfileShareFacts> draft) {
    var stored =
        values(draft)
            .onConflict(NOTIFICATION.RECIPIENT_ACCOUNT_ID, NOTIFICATION.KIND, NOTIFICATION.SHARE_ID)
            .where(
                NOTIFICATION
                    .FORM
                    .eq(inline(NotificationForm.NEWS))
                    .and(NOTIFICATION.READ_AT.isNull()))
            .doUpdate()
            .set(
                NOTIFICATION.LATEST_EVENT_AT,
                greatest(NOTIFICATION.LATEST_EVENT_AT, DSL.excluded(NOTIFICATION.LATEST_EVENT_AT)))
            .returningResult(
                NOTIFICATION.ID,
                NOTIFICATION.PROFILE_NAME,
                NOTIFICATION.SHARE_ANSWER,
                NOTIFICATION.SHARE_ENDED_BY,
                INSERTED)
            .fetchSingle();
    var notificationId = stored.value1();
    if (Boolean.TRUE.equals(stored.value5())) {
      return new UnreadNews.Made<>(notificationId);
    }

    return new UnreadNews.Moved<>(
        notificationId,
        new ProfileShareFacts(
            stored.value2(),
            Optional.ofNullable(stored.value3()).map(NotificationRepositoryCustomImpl::answerOf),
            Optional.ofNullable(stored.value4()).map(NotificationRepositoryCustomImpl::endedByOf)));
  }

  @Override
  public void replaceFacts(UUID notificationId, ProfileShareFacts facts) {
    dsl.update(NOTIFICATION)
        .set(NOTIFICATION.PROFILE_NAME, facts.profileName())
        .set(NOTIFICATION.SHARE_ANSWER, answer(facts))
        .set(NOTIFICATION.SHARE_ENDED_BY, endedBy(facts))
        .where(NOTIFICATION.ID.eq(notificationId))
        .execute();
  }

  private InsertSetMoreStep<NotificationRecord> values(
      NotificationDraft<NotificationKind, ProfileShareFacts> draft) {
    var facts = draft.facts();
    var kind = draft.kind();
    return dsl.insertInto(NOTIFICATION)
        .set(NOTIFICATION.RECIPIENT_ACCOUNT_ID, draft.recipientAccountId())
        .set(NOTIFICATION.KIND, storedKind(kind))
        .set(NOTIFICATION.FORM, NotificationForm.valueOf(kind.getForm().name()))
        .set(NOTIFICATION.SHARE_ID, draft.subjectId())
        .set(NOTIFICATION.PROFILE_NAME, facts.profileName())
        .set(NOTIFICATION.SHARE_ANSWER, answer(facts))
        .set(NOTIFICATION.SHARE_ENDED_BY, endedBy(facts))
        .set(NOTIFICATION.LATEST_EVENT_AT, draft.at().atOffset(ZoneOffset.UTC));
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.server.jooq.generated.enums.NotificationKind storedKind(
      NotificationKind kind) {
    return com.streamarr.server.jooq.generated.enums.NotificationKind.valueOf(kind.name());
  }

  private static ProfileShareAnswer answer(ProfileShareFacts facts) {
    return facts.answer().map(Enum::name).map(ProfileShareAnswer::valueOf).orElse(null);
  }

  private static ProfileShareEndedBy endedBy(ProfileShareFacts facts) {
    return facts.endedBy().map(Enum::name).map(ProfileShareEndedBy::valueOf).orElse(null);
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.server.domain.notification.ProfileShareAnswer answerOf(
      ProfileShareAnswer stored) {
    return com.streamarr.server.domain.notification.ProfileShareAnswer.valueOf(stored.name());
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.server.domain.notification.ProfileShareEndedBy endedByOf(
      ProfileShareEndedBy stored) {
    return com.streamarr.server.domain.notification.ProfileShareEndedBy.valueOf(stored.name());
  }
}
