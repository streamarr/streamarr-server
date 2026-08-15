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

  /**
   * Builds the authenticated user's account view and selectable profiles.
   *
   * @param identity the authenticated user's identity and token scope
   * @return the account, token scope, and selectable profiles
   * @throws AuthenticationRequiredException if the account cannot be found
   */
  @Transactional(readOnly = true)
  public MeView meView(AuthenticatedIdentity identity) {
    var account =
        accountRepository
            .findById(identity.accountId())
            .orElseThrow(AuthenticationRequiredException::new);
    var profiles =
        profileAvailabilityService.selectableProfiles(account.getId(), identity.profileId());
    return new MeView(account, identity.scope(), profiles);
  }

  public record MeView(
      UserAccount account,
      TokenScope scope,
      List<ProfileAvailabilityService.SelectableProfile> profiles) {}
}
