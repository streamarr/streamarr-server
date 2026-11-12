package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.enums.ProfileManagerInvitationStatus.INVALIDATED;
import static com.streamarr.server.jooq.generated.enums.ProfileManagerInvitationStatus.PENDING;
import static com.streamarr.server.jooq.generated.enums.ProfileManagerInvitationStatus.valueOf;
import static com.streamarr.server.jooq.generated.tables.ProfileManagerInvitation.PROFILE_MANAGER_INVITATION;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.lower;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.val;

import com.streamarr.server.domain.auth.ChangedManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import com.streamarr.server.domain.auth.ProfileManagerInvitationsFilter;
import com.streamarr.server.domain.pagination.Row;
import com.streamarr.server.domain.pagination.Seek;
import com.streamarr.server.jooq.generated.tables.records.ProfileManagerInvitationRecord;
import com.streamarr.server.repositories.JooqQueryHelper;
import com.streamarr.server.repositories.Keyset;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.UpdateResultStep;

@RequiredArgsConstructor
public class ProfileManagerInvitationRepositoryCustomImpl
    implements ProfileManagerInvitationRepositoryCustom {

  // The columns a write returns to name each invitation it changed.
  private static final List<Field<?>> CHANGED_INVITATION =
      List.of(
          PROFILE_MANAGER_INVITATION.ID,
          PROFILE_MANAGER_INVITATION.PROFILE_ID,
          PROFILE_MANAGER_INVITATION.INVITER_ACCOUNT_ID,
          PROFILE_MANAGER_INVITATION.RECIPIENT_ACCOUNT_ID,
          PROFILE_MANAGER_INVITATION.STATUS);

  private final DSLContext dsl;
  private final EntityManager entityManager;

  @Override
  public List<Row<ProfileManagerInvitation>> rowsPendingForProfile(
      ProfileManagerInvitationsFilter filter, Instant now, Seek seek) {
    var ofTheProfile = PROFILE_MANAGER_INVITATION.PROFILE_ID.eq(filter.profileId());
    return rowsPendingBeyond(
        filter.asItsManagersReadThem() ? ofTheProfile : ofTheProfile.and(answerable()), now, seek);
  }

  @Override
  public List<Row<ProfileManagerInvitation>> rowsPendingForRecipient(
      UUID recipientAccountId, Instant now, Seek seek) {
    return rowsPendingBeyond(
        PROFILE_MANAGER_INVITATION.RECIPIENT_ACCOUNT_ID.eq(recipientAccountId).and(answerable()),
        now,
        seek);
  }

  private List<Row<ProfileManagerInvitation>> rowsPendingBeyond(
      Condition scope, Instant now, Seek seek) {
    var keyset =
        Keyset.<OffsetDateTime>builder()
            .sortValue(PROFILE_MANAGER_INVITATION.CREATED_ON)
            .itemId(PROFILE_MANAGER_INVITATION.ID)
            .build();
    var live =
        scope
            .and(
                PROFILE_MANAGER_INVITATION.STATUS.eq(
                    inline(jooqStatus(ProfileManagerInvitationStatus.PENDING))))
            .and(PROFILE_MANAGER_INVITATION.EXPIRES_AT.gt(now.atOffset(ZoneOffset.UTC)));
    var query = keyset.rowsBeyond(keyset.selectFrom(dsl, PROFILE_MANAGER_INVITATION), live, seek);
    return JooqQueryHelper.nativeRows(entityManager, query, ProfileManagerInvitation.class, keyset);
  }

  @Override
  public int countLivePendingForProfileExceptTo(UUID profileId, String email, Instant now) {
    return dsl.fetchCount(
        PROFILE_MANAGER_INVITATION,
        PROFILE_MANAGER_INVITATION
            .PROFILE_ID
            .eq(profileId)
            .and(pending())
            .and(PROFILE_MANAGER_INVITATION.EXPIRES_AT.gt(now.atOffset(ZoneOffset.UTC)))
            .and(lower(PROFILE_MANAGER_INVITATION.RECIPIENT_EMAIL).ne(lower(val(email)))));
  }

  private Optional<ChangedManagerInvitation> tryTransitionPending(
      Condition which, ProfileManagerInvitationStatus target, Instant now) {
    return onlyChanged(
        changed(
            dsl.update(PROFILE_MANAGER_INVITATION)
                .set(PROFILE_MANAGER_INVITATION.STATUS, jooqStatus(target))
                .set(PROFILE_MANAGER_INVITATION.DECIDED_AT, now.atOffset(ZoneOffset.UTC))
                .where(which)
                .and(pending())
                .and(PROFILE_MANAGER_INVITATION.EXPIRES_AT.gt(now.atOffset(ZoneOffset.UTC)))
                .returning(CHANGED_INVITATION)));
  }

  // The inviter cancels an invitation that still waits to the Profile's side, whatever became of
  // the Account it names.
  @Override
  public Optional<ChangedManagerInvitation> tryCancelPending(UUID invitationId, Instant now) {
    return tryTransitionPending(
        PROFILE_MANAGER_INVITATION.ID.eq(invitationId),
        ProfileManagerInvitationStatus.CANCELED,
        now);
  }

  @Override
  public Optional<ChangedManagerInvitation> tryAcceptPending(UUID invitationId, Instant now) {
    return tryTransitionPending(
        PROFILE_MANAGER_INVITATION.ID.eq(invitationId).and(answerable()),
        ProfileManagerInvitationStatus.ACCEPTED,
        now);
  }

  @Override
  public Optional<ChangedManagerInvitation> tryDeclinePending(UUID invitationId, Instant now) {
    return tryTransitionPending(
        PROFILE_MANAGER_INVITATION.ID.eq(invitationId).and(answerable()),
        ProfileManagerInvitationStatus.DECLINED,
        now);
  }

  @Override
  public List<ChangedManagerInvitation> tryInvalidatePending(
      UUID invitationId, String reason, Instant now) {
    return invalidate(PROFILE_MANAGER_INVITATION.ID.eq(invitationId), reason, now);
  }

  @Override
  public List<ChangedManagerInvitation> invalidatePendingByProfileIdAndRecipientEmail(
      UUID profileId, String email, String reason, Instant now) {
    return invalidate(
        PROFILE_MANAGER_INVITATION
            .PROFILE_ID
            .eq(profileId)
            .and(lower(PROFILE_MANAGER_INVITATION.RECIPIENT_EMAIL).eq(lower(val(email)))),
        reason,
        now);
  }

  @Override
  public List<ChangedManagerInvitation> invalidatePendingByProfileIdAndRecipientAccountId(
      UUID profileId, UUID recipientAccountId, String reason, Instant now) {
    return invalidateForItsAccount(
        PROFILE_MANAGER_INVITATION
            .PROFILE_ID
            .eq(profileId)
            .and(PROFILE_MANAGER_INVITATION.RECIPIENT_ACCOUNT_ID.eq(recipientAccountId)),
        reason,
        now);
  }

  @Override
  public List<ChangedManagerInvitation> invalidatePendingByProfileId(
      UUID profileId, String reason, Instant now) {
    return invalidate(PROFILE_MANAGER_INVITATION.PROFILE_ID.eq(profileId), reason, now);
  }

  @Override
  public List<ChangedManagerInvitation> invalidatePendingInvitationsByInviterAccountIdAndProfileId(
      UUID inviterAccountId, UUID profileId, String reason, Instant now) {
    return invalidate(
        PROFILE_MANAGER_INVITATION
            .INVITER_ACCOUNT_ID
            .eq(inviterAccountId)
            .and(PROFILE_MANAGER_INVITATION.PROFILE_ID.eq(profileId)),
        reason,
        now);
  }

  @Override
  public List<ChangedManagerInvitation> invalidatePendingInvitedBy(
      UUID inviterAccountId, String reason, Instant now) {
    return invalidate(
        PROFILE_MANAGER_INVITATION.INVITER_ACCOUNT_ID.eq(inviterAccountId), reason, now);
  }

  @Override
  public List<ChangedManagerInvitation> invalidatePendingByRecipientAccountId(
      UUID recipientAccountId, String reason, Instant now) {
    return invalidateForItsAccount(
        PROFILE_MANAGER_INVITATION.RECIPIENT_ACCOUNT_ID.eq(recipientAccountId), reason, now);
  }

  @Override
  public List<UUID> findIdsByProfileId(UUID profileId) {
    return dsl.select(PROFILE_MANAGER_INVITATION.ID)
        .from(PROFILE_MANAGER_INVITATION)
        .where(PROFILE_MANAGER_INVITATION.PROFILE_ID.eq(profileId))
        .fetch(PROFILE_MANAGER_INVITATION.ID);
  }

  private List<ChangedManagerInvitation> invalidate(Condition scope, String reason, Instant now) {
    var expired = materializeExpiredPending(scope, now);
    var invalidated =
        changed(
            dsl.update(PROFILE_MANAGER_INVITATION)
                .set(PROFILE_MANAGER_INVITATION.STATUS, INVALIDATED)
                .set(PROFILE_MANAGER_INVITATION.INVALIDATION_REASON, reason)
                .set(PROFILE_MANAGER_INVITATION.DECIDED_AT, now.atOffset(ZoneOffset.UTC))
                .where(scope)
                .and(pending())
                .returning(CHANGED_INVITATION));
    return Stream.concat(expired.stream(), invalidated.stream()).toList();
  }

  // The status keeps what the Profile's side reads, to which the invitation still waits.
  private List<ChangedManagerInvitation> invalidateForItsAccount(
      Condition scope, String reason, Instant now) {
    var expired = materializeExpiredPending(scope, now);
    var invalidated =
        changed(
            dsl.update(PROFILE_MANAGER_INVITATION)
                .set(
                    PROFILE_MANAGER_INVITATION.RECIPIENT_INVALIDATED_AT,
                    now.atOffset(ZoneOffset.UTC))
                .set(PROFILE_MANAGER_INVITATION.RECIPIENT_INVALIDATION_REASON, reason)
                .where(scope)
                .and(pending())
                .and(answerable())
                .returning(CHANGED_INVITATION));
    return Stream.concat(expired.stream(), invalidated.stream()).toList();
  }

  @Override
  public List<ChangedManagerInvitation> materializeExpiredPending(Instant now) {
    return materializeExpiredPending(noCondition(), now);
  }

  private List<ChangedManagerInvitation> materializeExpiredPending(Condition scope, Instant now) {
    var nowUtc = now.atOffset(ZoneOffset.UTC);
    return changed(
        dsl.update(PROFILE_MANAGER_INVITATION)
            .set(
                PROFILE_MANAGER_INVITATION.STATUS,
                jooqStatus(ProfileManagerInvitationStatus.EXPIRED))
            .set(PROFILE_MANAGER_INVITATION.DECIDED_AT, nowUtc)
            .where(scope)
            .and(pending())
            .and(PROFILE_MANAGER_INVITATION.EXPIRES_AT.le(nowUtc))
            .returning(CHANGED_INVITATION));
  }

  private static List<ChangedManagerInvitation> changed(
      UpdateResultStep<ProfileManagerInvitationRecord> update) {
    return update.fetch().map(ProfileManagerInvitationRepositoryCustomImpl::changedInvitation);
  }

  private static ChangedManagerInvitation changedInvitation(
      ProfileManagerInvitationRecord invitationRecord) {
    return new ChangedManagerInvitation(
        invitationRecord.getId(),
        invitationRecord.getProfileId(),
        invitationRecord.getInviterAccountId(),
        Optional.ofNullable(invitationRecord.getRecipientAccountId()),
        ProfileManagerInvitationStatus.valueOf(invitationRecord.getStatus().name()));
  }

  // Each single-invitation write names its invitation by id, so it changes at most one row.
  private static Optional<ChangedManagerInvitation> onlyChanged(
      List<ChangedManagerInvitation> changed) {
    return changed.stream().findFirst();
  }

  private static Condition pending() {
    return PROFILE_MANAGER_INVITATION.STATUS.eq(PENDING);
  }

  // Not invalidated for its invited Account, which could still answer it.
  private static Condition answerable() {
    return PROFILE_MANAGER_INVITATION.RECIPIENT_INVALIDATED_AT.isNull();
  }

  // The domain and generated jOOQ status enums share a simple name.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.server.jooq.generated.enums.ProfileManagerInvitationStatus
      jooqStatus(ProfileManagerInvitationStatus status) {
    return valueOf(status.name());
  }
}
