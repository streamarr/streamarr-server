package com.streamarr.server.repositories.notification;

import static com.streamarr.server.jooq.generated.tables.Notification.NOTIFICATION;
import static com.streamarr.server.jooq.generated.tables.Profile.PROFILE;
import static com.streamarr.server.jooq.generated.tables.ProfileHouseholdShare.PROFILE_HOUSEHOLD_SHARE;
import static com.streamarr.server.jooq.generated.tables.ProfileManager.PROFILE_MANAGER;
import static com.streamarr.server.jooq.generated.tables.UserAccount.USER_ACCOUNT;
import static com.streamarr.server.repositories.auth.ProfileShareStatusReads.readsAs;
import static org.jooq.impl.DSL.greatest;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.val;

import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.notification.GuardedNotification;
import com.streamarr.server.domain.notification.Notification;
import com.streamarr.server.domain.notification.NotificationDraft;
import com.streamarr.server.domain.notification.NotificationKind;
import com.streamarr.server.domain.notification.NotificationSubject;
import com.streamarr.server.domain.notification.ProfileShareFacts;
import com.streamarr.server.domain.notification.ShareSides;
import com.streamarr.server.domain.notification.UnreadNews;
import com.streamarr.server.domain.pagination.Row;
import com.streamarr.server.domain.pagination.Seek;
import com.streamarr.server.jooq.generated.enums.HouseholdRole;
import com.streamarr.server.jooq.generated.enums.NotificationForm;
import com.streamarr.server.jooq.generated.enums.ProfileShareAnswer;
import com.streamarr.server.jooq.generated.enums.ProfileShareEndedBy;
import com.streamarr.server.jooq.generated.tables.records.NotificationRecord;
import com.streamarr.server.repositories.JooqQueryHelper;
import com.streamarr.server.repositories.Keyset;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.InsertSetMoreStep;
import org.jooq.Record1;
import org.jooq.Records;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

@RequiredArgsConstructor
public class NotificationRepositoryCustomImpl implements NotificationRepositoryCustom {

  // PostgreSQL leaves xmax at zero on a row the statement inserted, and sets it on a row the
  // statement's ON CONFLICT DO UPDATE took.
  private static final Field<Boolean> INSERTED =
      DSL.field("(xmax = 0)", SQLDataType.BOOLEAN).as("inserted");

  private static final Keyset<OffsetDateTime> NEWEST_FIRST =
      Keyset.<OffsetDateTime>builder()
          .sortValue(NOTIFICATION.LATEST_EVENT_AT)
          .itemId(NOTIFICATION.ID)
          .build();

  private final DSLContext dsl;
  private final EntityManager entityManager;

  @Override
  public List<Row<Notification>> rowsOfRecipient(UUID recipientAccountId, Seek seek) {
    var query =
        NEWEST_FIRST.rowsBeyond(
            NEWEST_FIRST.selectFrom(dsl, NOTIFICATION),
            NOTIFICATION.RECIPIENT_ACCOUNT_ID.eq(recipientAccountId),
            seek);
    return JooqQueryHelper.nativeRows(entityManager, query, Notification.class, NEWEST_FIRST);
  }

  @Override
  public List<Row<Notification>> rowsOfRecipientWithIds(
      UUID recipientAccountId, Collection<UUID> notificationIds) {
    if (notificationIds.isEmpty()) {
      return List.of();
    }

    var query =
        NEWEST_FIRST
            .selectFrom(dsl, NOTIFICATION)
            .where(NOTIFICATION.RECIPIENT_ACCOUNT_ID.eq(recipientAccountId))
            .and(NOTIFICATION.ID.in(notificationIds));
    return JooqQueryHelper.nativeRows(entityManager, query, Notification.class, NEWEST_FIRST);
  }

  @Override
  public List<GuardedNotification> findRequestsAbout(UUID shareId) {
    return dsl.select(
            NOTIFICATION.ID,
            NOTIFICATION.RECIPIENT_ACCOUNT_ID,
            PROFILE_HOUSEHOLD_SHARE.PROFILE_ID,
            PROFILE_HOUSEHOLD_SHARE.HOUSEHOLD_ID)
        .from(NOTIFICATION)
        .join(PROFILE_HOUSEHOLD_SHARE)
        .on(PROFILE_HOUSEHOLD_SHARE.ID.eq(NOTIFICATION.SHARE_ID))
        .where(NOTIFICATION.SHARE_ID.eq(shareId))
        .and(NOTIFICATION.FORM.eq(inline(NotificationForm.REQUEST)))
        .fetch(Records.mapping(GuardedNotification::new));
  }

  @Override
  public List<Notification> findUnreadOfRecipient(UUID recipientAccountId, Instant now) {
    return unreadOf(recipientAccountId, DSL.noCondition(), now);
  }

  @Override
  public List<Notification> findUnreadOfRecipientThrough(
      UUID recipientAccountId, Seek through, Instant now) {
    return unreadOf(recipientAccountId, NEWEST_FIRST.beyond(through), now);
  }

  @Override
  public List<UUID> markReadThrough(
      Collection<UUID> notificationIds, UUID recipientAccountId, Seek through, Instant now) {
    if (notificationIds.isEmpty()) {
      return List.of();
    }

    return dsl.update(NOTIFICATION)
        .set(NOTIFICATION.READ_AT, now.atOffset(ZoneOffset.UTC))
        .where(NOTIFICATION.ID.in(notificationIds))
        .and(NOTIFICATION.RECIPIENT_ACCOUNT_ID.eq(recipientAccountId))
        .and(NOTIFICATION.READ_AT.isNull())
        .and(NEWEST_FIRST.beyond(through))
        .returning(NOTIFICATION.ID)
        .fetch(NOTIFICATION.ID);
  }

  @Override
  public boolean markRead(UUID notificationId, UUID recipientAccountId, Instant now) {
    return dsl.update(NOTIFICATION)
            .set(NOTIFICATION.READ_AT, now.atOffset(ZoneOffset.UTC))
            .where(NOTIFICATION.ID.eq(notificationId))
            .and(NOTIFICATION.RECIPIENT_ACCOUNT_ID.eq(recipientAccountId))
            .and(NOTIFICATION.READ_AT.isNull())
            .execute()
        > 0;
  }

  @Override
  public Optional<Notification> findByIdAndReloadFromDatabase(UUID notificationId) {
    var entity = entityManager.find(Notification.class, notificationId);
    if (entity == null) {
      return Optional.empty();
    }

    entityManager.refresh(entity);
    return Optional.of(entity);
  }

  private List<Notification> unreadOf(UUID recipientAccountId, Condition among, Instant now) {
    var query =
        dsl.selectFrom(NOTIFICATION)
            .where(NOTIFICATION.RECIPIENT_ACCOUNT_ID.eq(recipientAccountId))
            .and(NOTIFICATION.READ_AT.isNull())
            .and(NOTIFICATION.FORM.eq(inline(NotificationForm.NEWS)).or(requestWaitsAt(now)))
            .and(among);
    return JooqQueryHelper.nativeQuery(entityManager, query, Notification.class);
  }

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

  @Override
  public int deleteHistoryBefore(Instant cutoff, Instant now) {
    var readNews =
        NOTIFICATION.FORM.eq(inline(NotificationForm.NEWS)).and(NOTIFICATION.READ_AT.isNotNull());
    var resolvedRequest =
        NOTIFICATION.FORM.eq(inline(NotificationForm.REQUEST)).andNot(requestWaitsAt(now));
    return dsl.deleteFrom(NOTIFICATION)
        .where(NOTIFICATION.LATEST_EVENT_AT.lt(cutoff.atOffset(ZoneOffset.UTC)))
        .and(readNews.or(resolvedRequest))
        .execute();
  }

  @Override
  public List<Notification> findUnreadNewsBefore(Instant cutoff) {
    return JooqQueryHelper.nativeQuery(
        entityManager,
        dsl.selectFrom(NOTIFICATION).where(unreadNewsBefore(cutoff)),
        Notification.class);
  }

  @Override
  public int deleteUnreadNewsBefore(Collection<UUID> notificationIds, Instant cutoff) {
    return dsl.deleteFrom(NOTIFICATION)
        .where(NOTIFICATION.ID.in(notificationIds))
        .and(unreadNewsBefore(cutoff))
        .execute();
  }

  /** A request that waits at the instant: its subject still waits, as its kind's subject says. */
  private static Condition requestWaitsAt(Instant now) {
    return NOTIFICATION
        .FORM
        .eq(inline(NotificationForm.REQUEST))
        .and(
            DSL.or(
                Arrays.stream(NotificationSubject.values())
                    .map(subject -> kindAbout(subject).and(subjectWaitsAt(subject, now)))
                    .toList()));
  }

  private static Condition subjectWaitsAt(NotificationSubject subject, Instant now) {
    return switch (subject) {
      case PROFILE_SHARE ->
          DSL.exists(
              DSL.selectOne()
                  .from(PROFILE_HOUSEHOLD_SHARE)
                  .where(PROFILE_HOUSEHOLD_SHARE.ID.eq(NOTIFICATION.SHARE_ID))
                  .and(waitingAt(now)));
    };
  }

  private static Condition kindAbout(NotificationSubject subject) {
    return NOTIFICATION.KIND.in(
        Arrays.stream(NotificationKind.values())
            .filter(kind -> kind.getSubject() == subject)
            .map(NotificationRepositoryCustomImpl::storedKind)
            .map(DSL::inline)
            .toList());
  }

  // A pending offer whose time ran out no longer waits, before the expiry sweep stores it.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static Condition waitingAt(Instant now) {
    return readsAs(
        com.streamarr.server.jooq.generated.enums.ProfileShareStatus.PENDING,
        val(now.atOffset(ZoneOffset.UTC)));
  }

  // Disabling an Account changes nothing for its Notifications, and the guard denies a disabled
  // Account everything, so its unread news is never a candidate.
  private static Condition unreadNewsBefore(Instant cutoff) {
    return NOTIFICATION
        .FORM
        .eq(inline(NotificationForm.NEWS))
        .and(NOTIFICATION.READ_AT.isNull())
        .and(NOTIFICATION.LATEST_EVENT_AT.lt(cutoff.atOffset(ZoneOffset.UTC)))
        .andNotExists(
            DSL.selectOne()
                .from(USER_ACCOUNT)
                .where(USER_ACCOUNT.ID.eq(NOTIFICATION.RECIPIENT_ACCOUNT_ID))
                .and(USER_ACCOUNT.ENABLED.isFalse()));
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
