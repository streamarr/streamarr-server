package com.streamarr.server.repositories.auth;

import java.util.UUID;

public interface ProfileManagerInvitationRepositoryCustom {

  /**
       * Inserts a pending profile manager invitation when one does not already exist.
       *
       * @param profileId the profile associated with the invitation
       * @param invitingAccountId the account sending the invitation
       * @param invitedAccountId the account receiving the invitation
       * @return the result of the insertion attempt
       */
      ProfileManagerInvitationInsertResult insertPendingIfAbsent(
      UUID profileId, UUID invitingAccountId, UUID invitedAccountId);
}
