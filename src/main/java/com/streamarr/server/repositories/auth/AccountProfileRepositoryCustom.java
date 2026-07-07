package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.AccountProfile;

public interface AccountProfileRepositoryCustom {

  void linkProfile(AccountProfile link);

  void revokeProfileLink(AccountProfile link);
}
