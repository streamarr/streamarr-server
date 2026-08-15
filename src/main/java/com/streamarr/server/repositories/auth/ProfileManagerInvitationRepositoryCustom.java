package com.streamarr.server.repositories.auth;

import java.util.UUID;

public interface ProfileManagerInvitationRepositoryCustom {

  ProfileManagerInvitationInsertResult insertPendingIfAbsent(
      UUID profileId, UUID invitingAccountId, UUID invitedAccountId);
}
