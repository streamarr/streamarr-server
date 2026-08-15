package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.enums.ProfileManagerInvitationStatus.PENDING;
import static com.streamarr.server.jooq.generated.tables.ProfileManagerInvitation.PROFILE_MANAGER_INVITATION;

import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.data.domain.AuditorAware;

@RequiredArgsConstructor
public class ProfileManagerInvitationRepositoryCustomImpl
    implements ProfileManagerInvitationRepositoryCustom {

  private final DSLContext dsl;
  private final AuditorAware<UUID> auditorAware;

  @Override
  public ProfileManagerInvitationInsertResult insertPendingIfAbsent(
      UUID profileId, UUID invitingAccountId, UUID invitedAccountId) {
    var auditUser = auditorAware.getCurrentAuditor().orElse(null);
    var pending = PENDING;
    while (true) {
      var insertedId =
          dsl.insertInto(PROFILE_MANAGER_INVITATION)
              .set(PROFILE_MANAGER_INVITATION.ID, UUID.randomUUID())
              .set(PROFILE_MANAGER_INVITATION.PROFILE_ID, profileId)
              .set(PROFILE_MANAGER_INVITATION.INVITING_ACCOUNT_ID, invitingAccountId)
              .set(PROFILE_MANAGER_INVITATION.INVITED_ACCOUNT_ID, invitedAccountId)
              .set(PROFILE_MANAGER_INVITATION.STATUS, pending)
              .set(PROFILE_MANAGER_INVITATION.CREATED_BY, auditUser)
              .set(PROFILE_MANAGER_INVITATION.LAST_MODIFIED_BY, auditUser)
              .onConflict(
                  PROFILE_MANAGER_INVITATION.PROFILE_ID,
                  PROFILE_MANAGER_INVITATION.INVITED_ACCOUNT_ID)
              .where(PROFILE_MANAGER_INVITATION.STATUS.eq(pending))
              .doNothing()
              .returning(PROFILE_MANAGER_INVITATION.ID)
              .fetchOne(PROFILE_MANAGER_INVITATION.ID);
      if (insertedId != null) {
        return new ProfileManagerInvitationInsertResult(
            invitation(insertedId, profileId, invitingAccountId, invitedAccountId), true);
      }

      var existing =
          dsl.select(PROFILE_MANAGER_INVITATION.ID, PROFILE_MANAGER_INVITATION.INVITING_ACCOUNT_ID)
              .from(PROFILE_MANAGER_INVITATION)
              .where(PROFILE_MANAGER_INVITATION.PROFILE_ID.eq(profileId))
              .and(PROFILE_MANAGER_INVITATION.INVITED_ACCOUNT_ID.eq(invitedAccountId))
              .and(PROFILE_MANAGER_INVITATION.STATUS.eq(pending))
              .forUpdate()
              .fetchOptional();
      if (existing.isPresent()) {
        var existingInvitation = existing.orElseThrow();
        return new ProfileManagerInvitationInsertResult(
            invitation(
                existingInvitation.value1(),
                profileId,
                existingInvitation.value2(),
                invitedAccountId),
            false);
      }
    }
  }

  @Override
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  public Optional<ProfileManagerInvitation> transitionPending(
      ProfileManagerInvitationTransition transition) {
    var condition =
        PROFILE_MANAGER_INVITATION
            .ID
            .eq(transition.invitationId())
            .and(PROFILE_MANAGER_INVITATION.STATUS.eq(PENDING));
    if (transition.invitedAccountId() != null) {
      condition =
          condition.and(
              PROFILE_MANAGER_INVITATION.INVITED_ACCOUNT_ID.eq(transition.invitedAccountId()));
    }
    if (transition.expectedProfileId() != null) {
      condition =
          condition.and(PROFILE_MANAGER_INVITATION.PROFILE_ID.eq(transition.expectedProfileId()));
    }

    var status =
        com.streamarr.server.jooq.generated.enums.ProfileManagerInvitationStatus.valueOf(
            transition.status().name());
    return dsl.update(PROFILE_MANAGER_INVITATION)
        .set(PROFILE_MANAGER_INVITATION.STATUS, status)
        .set(PROFILE_MANAGER_INVITATION.LAST_MODIFIED_ON, OffsetDateTime.now(ZoneOffset.UTC))
        .set(
            PROFILE_MANAGER_INVITATION.LAST_MODIFIED_BY,
            auditorAware.getCurrentAuditor().orElse(null))
        .where(condition)
        .returning(
            PROFILE_MANAGER_INVITATION.ID,
            PROFILE_MANAGER_INVITATION.PROFILE_ID,
            PROFILE_MANAGER_INVITATION.INVITING_ACCOUNT_ID,
            PROFILE_MANAGER_INVITATION.INVITED_ACCOUNT_ID)
        .fetchOptional()
        .map(
            updated ->
                invitation(
                    updated.getId(),
                    updated.getProfileId(),
                    updated.getInvitingAccountId(),
                    updated.getInvitedAccountId(),
                    transition.status()));
  }

  private ProfileManagerInvitation invitation(
      UUID id, UUID profileId, UUID invitingAccountId, UUID invitedAccountId) {
    return invitation(
        id, profileId, invitingAccountId, invitedAccountId, ProfileManagerInvitationStatus.PENDING);
  }

  private ProfileManagerInvitation invitation(
      UUID id,
      UUID profileId,
      UUID invitingAccountId,
      UUID invitedAccountId,
      ProfileManagerInvitationStatus status) {
    return ProfileManagerInvitation.builder()
        .id(id)
        .profileId(profileId)
        .invitingAccountId(invitingAccountId)
        .invitedAccountId(invitedAccountId)
        .status(status)
        .build();
  }
}
