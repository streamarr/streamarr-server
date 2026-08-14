package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.ProfileManagerInvitation.PROFILE_MANAGER_INVITATION;

import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
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
  public ProfileManagerInvitation insertPendingIfAbsent(
      UUID profileId, UUID invitingAccountId, UUID invitedAccountId) {
    var id = UUID.randomUUID();
    var auditUser = auditorAware.getCurrentAuditor().orElse(null);
    var pending = com.streamarr.server.jooq.generated.enums.ProfileManagerInvitationStatus.PENDING;
    var insertedId =
        dsl.insertInto(PROFILE_MANAGER_INVITATION)
            .set(PROFILE_MANAGER_INVITATION.ID, id)
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
      return invitation(insertedId, profileId, invitingAccountId, invitedAccountId);
    }

    var existing =
        dsl.select(PROFILE_MANAGER_INVITATION.ID, PROFILE_MANAGER_INVITATION.INVITING_ACCOUNT_ID)
            .from(PROFILE_MANAGER_INVITATION)
            .where(PROFILE_MANAGER_INVITATION.PROFILE_ID.eq(profileId))
            .and(PROFILE_MANAGER_INVITATION.INVITED_ACCOUNT_ID.eq(invitedAccountId))
            .and(PROFILE_MANAGER_INVITATION.STATUS.eq(pending))
            .fetchSingle();
    return invitation(existing.value1(), profileId, existing.value2(), invitedAccountId);
  }

  private ProfileManagerInvitation invitation(
      UUID id, UUID profileId, UUID invitingAccountId, UUID invitedAccountId) {
    return ProfileManagerInvitation.builder()
        .id(id)
        .profileId(profileId)
        .invitingAccountId(invitingAccountId)
        .invitedAccountId(invitedAccountId)
        .status(ProfileManagerInvitationStatus.PENDING)
        .build();
  }
}
