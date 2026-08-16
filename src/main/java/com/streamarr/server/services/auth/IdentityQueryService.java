package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read model behind the flat account and portable-profile {@code me} query. */
@Service
@RequiredArgsConstructor
public class IdentityQueryService {

  private final UserAccountRepository accountRepository;
  private final ProfileAvailabilityService profileAvailabilityService;

  @Transactional(readOnly = true)
  public MeView meView(AuthenticatedIdentity identity) {
    var account =
        accountRepository
            .findById(identity.accountId())
            .orElseThrow(AuthenticationRequiredException::new);
    var profiles = profileAvailabilityService.selectableProfiles(identity, identity.profileId());
    return new MeView(account, identity, profiles);
  }

  public record MeView(
      UserAccount account,
      AuthenticatedIdentity authority,
      List<ProfileAvailabilityService.SelectableProfile> profiles) {}
}
