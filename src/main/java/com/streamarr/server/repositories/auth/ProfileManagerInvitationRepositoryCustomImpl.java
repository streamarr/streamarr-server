package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.enums.ProfileManagerInvitationStatus.INVALIDATED;
import static com.streamarr.server.jooq.generated.enums.ProfileManagerInvitationStatus.PENDING;
import static com.streamarr.server.jooq.generated.enums.ProfileManagerInvitationStatus.valueOf;
import static com.streamarr.server.jooq.generated.tables.ProfileManagerInvitation.PROFILE_MANAGER_INVITATION;
import static org.jooq.impl.DSL.inline;

import com.streamarr.server.domain.auth.ChangedManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
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
      UUID profileId, Instant now, Seek seek) {
    return rowsPendingBeyond(PROFILE_MANAGER_INVITATION.PROFILE_ID.eq(profileId), now, seek);
  }

  @Override
  public List<Row<ProfileManagerInvitation>> rowsPendingForRecipient(
      UUID recipientAccountId, Instant now, Seek seek) {
    return rowsPendingBeyond(
        PROFILE_MANAGER_INVITATION.RECIPIENT_ACCOUNT_ID.eq(recipientAccountId), now, seek);
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

  private Optional<ChangedManagerInvitation> tryTransitionPending(
      UUID invitationId, ProfileManagerInvitationStatus target, Instant now) {
    return onlyChanged(
        changed(
            dsl.update(PROFILE_MANAGER_INVITATION)
                .set(PROFILE_MANAGER_INVITATION.STATUS, jooqStatus(target))
                .set(PROFILE_MANAGER_INVITATION.DECIDED_AT, now.atOffset(ZoneOffset.UTC))
                .where(PROFILE_MANAGER_INVITATION.ID.eq(invitationId))
                .and(pending())
                .and(PROFILE_MANAGER_INVITATION.EXPIRES_AT.gt(now.atOffset(ZoneOffset.UTC)))
                .returning(CHANGED_INVITATION)));
  }

  @Override
  public Optional<ChangedManagerInvitation> tryCancelPending(UUID invitationId, Instant now) {
    return tryTransitionPending(invitationId, ProfileManagerInvitationStatus.CANCELED, now);
  }

  @Override
  public Optional<ChangedManagerInvitation> tryAcceptPending(UUID invitationId, Instant now) {
    return tryTransitionPending(invitationId, ProfileManagerInvitationStatus.ACCEPTED, now);
  }

  @Override
  public Optional<ChangedManagerInvitation> tryDeclinePending(UUID invitationId, Instant now) {
    return tryTransitionPending(invitationId, ProfileManagerInvitationStatus.DECLINED, now);
  }

  @Override
  public List<ChangedManagerInvitation> tryInvalidatePending(
      UUID invitationId, String reason, Instant now) {
    return invalidate(PROFILE_MANAGER_INVITATION.ID.eq(invitationId), reason, now);
  }

  @Override
  public List<ChangedManagerInvitation> invalidatePendingByProfileIdAndRecipientAccountId(
      UUID profileId, UUID recipientAccountId, String reason, Instant now) {
    return invalidate(
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
    return invalidate(
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

  // The domain and generated jOOQ status enums share a simple name.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.server.jooq.generated.enums.ProfileManagerInvitationStatus
      jooqStatus(ProfileManagerInvitationStatus status) {
    return valueOf(status.name());
  }
}
