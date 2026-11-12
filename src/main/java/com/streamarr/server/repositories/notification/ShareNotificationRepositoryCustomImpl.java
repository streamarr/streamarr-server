package com.streamarr.server.repositories.notification;

import static com.streamarr.server.jooq.generated.tables.Profile.PROFILE;
import static com.streamarr.server.jooq.generated.tables.ProfileHouseholdShare.PROFILE_HOUSEHOLD_SHARE;
import static com.streamarr.server.jooq.generated.tables.ProfileManager.PROFILE_MANAGER;
import static com.streamarr.server.jooq.generated.tables.ShareNotification.SHARE_NOTIFICATION;
import static com.streamarr.server.jooq.generated.tables.UserAccount.USER_ACCOUNT;
import static org.jooq.impl.DSL.greatest;
import static org.jooq.impl.DSL.inline;

import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.notification.Notification;
import com.streamarr.server.domain.notification.NotificationDraft;
import com.streamarr.server.domain.notification.NotificationKind;
import com.streamarr.server.domain.notification.ProfileShareFacts;
import com.streamarr.server.domain.notification.ShareSides;
import com.streamarr.server.domain.notification.UnreadNews;
import com.streamarr.server.jooq.generated.enums.HouseholdRole;
import com.streamarr.server.jooq.generated.enums.NotificationForm;
import com.streamarr.server.jooq.generated.enums.ProfileShareAnswer;
import com.streamarr.server.jooq.generated.enums.ProfileShareEndedBy;
import com.streamarr.server.jooq.generated.enums.ProfileShareSide;
import com.streamarr.server.jooq.generated.tables.records.ShareNotificationRecord;
import com.streamarr.server.repositories.JooqQueryHelper;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.InsertSetMoreStep;
import org.jooq.Record1;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

@RequiredArgsConstructor
public class ShareNotificationRepositoryCustomImpl implements ShareNotificationRepositoryCustom {

  // PostgreSQL leaves xmax at zero on a row the statement inserted, and sets it on a row the
  // statement's ON CONFLICT DO UPDATE took.
  private static final Field<Boolean> INSERTED =
      DSL.field("(xmax = 0)", SQLDataType.BOOLEAN).as("inserted");

  private final DSLContext dsl;
  private final EntityManager entityManager;

  @Override
  public List<Notification> findRequestsAbout(UUID shareId) {
    var query =
        dsl.selectFrom(SHARE_NOTIFICATION)
            .where(SHARE_NOTIFICATION.SHARE_ID.eq(shareId))
            .and(SHARE_NOTIFICATION.FORM.eq(inline(NotificationForm.REQUEST)));
    return JooqQueryHelper.nativeQuery(entityManager, query, Notification.class);
  }

  @Override
  public List<UUID> markRead(
      Collection<UUID> notificationIds, UUID recipientAccountId, Instant now) {
    if (notificationIds.isEmpty()) {
      return List.of();
    }

    return dsl.update(SHARE_NOTIFICATION)
        .set(SHARE_NOTIFICATION.READ_AT, now.atOffset(ZoneOffset.UTC))
        .where(SHARE_NOTIFICATION.ID.in(notificationIds))
        .and(SHARE_NOTIFICATION.RECIPIENT_ACCOUNT_ID.eq(recipientAccountId))
        .and(SHARE_NOTIFICATION.READ_AT.isNull())
        .returning(SHARE_NOTIFICATION.ID)
        .fetch(SHARE_NOTIFICATION.ID);
  }

  @Override
  public UUID insert(NotificationDraft<NotificationKind, ProfileShareFacts> draft) {
    return values(draft).returning(SHARE_NOTIFICATION.ID).fetchSingle().getId();
  }

  @Override
  public UnreadNews<ProfileShareFacts> upsertUnreadNews(
      NotificationDraft<NotificationKind, ProfileShareFacts> draft) {
    var stored =
        values(draft)
            .onConflict(
                SHARE_NOTIFICATION.RECIPIENT_ACCOUNT_ID,
                SHARE_NOTIFICATION.KIND,
                SHARE_NOTIFICATION.SHARE_ID)
            .where(
                SHARE_NOTIFICATION
                    .FORM
                    .eq(inline(NotificationForm.NEWS))
                    .and(SHARE_NOTIFICATION.READ_AT.isNull()))
            .doUpdate()
            .set(
                SHARE_NOTIFICATION.LATEST_EVENT_AT,
                greatest(
                    SHARE_NOTIFICATION.LATEST_EVENT_AT,
                    DSL.excluded(SHARE_NOTIFICATION.LATEST_EVENT_AT)))
            .returningResult(
                SHARE_NOTIFICATION.ID,
                SHARE_NOTIFICATION.PROFILE_NAME,
                SHARE_NOTIFICATION.SHARE_ANSWER,
                SHARE_NOTIFICATION.SHARE_ENDED_BY,
                SHARE_NOTIFICATION.ADDRESSED_TO,
                INSERTED)
            .fetchSingle();
    var notificationId = stored.value1();
    if (Boolean.TRUE.equals(stored.value6())) {
      return new UnreadNews.Made<>(notificationId);
    }

    return new UnreadNews.Moved<>(
        notificationId,
        new ProfileShareFacts(
            stored.value2(),
            Optional.ofNullable(stored.value3())
                .map(ShareNotificationRepositoryCustomImpl::answerOf),
            Optional.ofNullable(stored.value4())
                .map(ShareNotificationRepositoryCustomImpl::endedByOf),
            addressedToOf(stored.value5())));
  }

  @Override
  public void replaceFacts(UUID notificationId, ProfileShareFacts facts) {
    dsl.update(SHARE_NOTIFICATION)
        .set(SHARE_NOTIFICATION.PROFILE_NAME, facts.profileName())
        .set(SHARE_NOTIFICATION.SHARE_ANSWER, answer(facts))
        .set(SHARE_NOTIFICATION.SHARE_ENDED_BY, endedBy(facts))
        .where(SHARE_NOTIFICATION.ID.eq(notificationId))
        .execute();
  }

  @Override
  public Optional<ShareSides> findShareSides(UUID shareId) {
    return dsl.select(
            PROFILE_HOUSEHOLD_SHARE.STATUS,
            PROFILE_HOUSEHOLD_SHARE.PROFILE_ID,
            PROFILE_HOUSEHOLD_SHARE.HOUSEHOLD_ID,
            PROFILE_HOUSEHOLD_SHARE.OFFERED_BY_ACCOUNT_ID,
            PROFILE.NAME)
        .from(PROFILE_HOUSEHOLD_SHARE)
        .join(PROFILE)
        .on(PROFILE.ID.eq(PROFILE_HOUSEHOLD_SHARE.PROFILE_ID))
        .where(PROFILE_HOUSEHOLD_SHARE.ID.eq(shareId))
        .fetchOptional(
            share ->
                ShareSides.builder()
                    .status(ProfileShareStatus.valueOf(share.value1().name()))
                    .profileName(share.value5())
                    .offeredByAccountId(Optional.ofNullable(share.value4()))
                    .offeringSide(offeringSide(share.value2(), share.value4()))
                    .householdSide(householdAdmins(share.value3()))
                    .build());
  }

  // The Profile's direct managers, its own Account while it is an unrestricted Adult Personal
  // Profile, and the Account that made the offer while it exists.
  private Set<UUID> offeringSide(UUID profileId, UUID offeredByAccountId) {
    var side =
        new HashSet<>(
            dsl.select(PROFILE_MANAGER.ACCOUNT_ID)
                .from(PROFILE_MANAGER)
                .where(PROFILE_MANAGER.PROFILE_ID.eq(profileId))
                .union(
                    DSL.select(USER_ACCOUNT.ID)
                        .from(USER_ACCOUNT)
                        .join(PROFILE)
                        .on(PROFILE.ID.eq(USER_ACCOUNT.PERSONAL_PROFILE_ID))
                        .where(PROFILE.ID.eq(profileId))
                        .and(PROFILE.RESTRICTED.isFalse()))
                .fetch(Record1::value1));
    if (offeredByAccountId != null) {
      side.add(offeredByAccountId);
    }

    return side;
  }

  private Set<UUID> householdAdmins(UUID householdId) {
    return Set.copyOf(
        dsl.select(USER_ACCOUNT.ID)
            .from(USER_ACCOUNT)
            .where(USER_ACCOUNT.HOUSEHOLD_ID.eq(householdId))
            .and(USER_ACCOUNT.HOUSEHOLD_ROLE.eq(HouseholdRole.ADMIN))
            .fetch(USER_ACCOUNT.ID));
  }

  private InsertSetMoreStep<ShareNotificationRecord> values(
      NotificationDraft<NotificationKind, ProfileShareFacts> draft) {
    var facts = draft.facts();
    var kind = draft.kind();
    return dsl.insertInto(SHARE_NOTIFICATION)
        .set(SHARE_NOTIFICATION.RECIPIENT_ACCOUNT_ID, draft.recipientAccountId())
        .set(SHARE_NOTIFICATION.KIND, storedKind(kind))
        .set(SHARE_NOTIFICATION.FORM, NotificationForm.valueOf(kind.getForm().name()))
        .set(SHARE_NOTIFICATION.SHARE_ID, draft.subjectId())
        .set(SHARE_NOTIFICATION.PROFILE_NAME, facts.profileName())
        .set(SHARE_NOTIFICATION.SHARE_ANSWER, answer(facts))
        .set(SHARE_NOTIFICATION.SHARE_ENDED_BY, endedBy(facts))
        .set(SHARE_NOTIFICATION.LATEST_EVENT_AT, draft.at().atOffset(ZoneOffset.UTC))
        .set(SHARE_NOTIFICATION.ADDRESSED_TO, ProfileShareSide.valueOf(facts.addressedTo().name()));
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
  private static com.streamarr.server.domain.notification.ProfileShareSide addressedToOf(
      ProfileShareSide stored) {
    return com.streamarr.server.domain.notification.ProfileShareSide.valueOf(stored.name());
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.server.domain.notification.ProfileShareEndedBy endedByOf(
      ProfileShareEndedBy stored) {
    return com.streamarr.server.domain.notification.ProfileShareEndedBy.valueOf(stored.name());
  }
}
