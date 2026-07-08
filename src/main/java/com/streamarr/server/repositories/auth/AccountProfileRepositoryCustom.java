package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.AccountProfile;

public interface AccountProfileRepositoryCustom {

  void linkProfile(AccountProfile link);

  /**
   * Revokes the link matching the given account, household, and profile ids.
   *
   * @return true if a link was revoked; false if no matching link existed (idempotent no-op)
   */
  boolean revokeProfileLink(AccountProfile link);
}
