package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.enums.ProfileManagerInvitationStatus.PENDING;
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

  /**
   * Creates a pending profile-manager invitation when one does not already exist.
   *
   * @param profileId          the profile associated with the invitation
   * @param invitingAccountId  the account sending the invitation
   * @param invitedAccountId   the account receiving the invitation
   * @return the invitation and whether it was newly created
   */
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

  /**
   * Creates a pending profile-manager invitation with the specified identifiers.
   *
   * @param id                 the invitation identifier
   * @param profileId          the profile identifier
   * @param invitingAccountId  the account sending the invitation
   * @param invitedAccountId   the account receiving the invitation
   * @return the pending profile-manager invitation
   */
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
