package com.streamarr.server.services.authorization.cedar;

import com.cedarpolicy.value.PrimBool;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * ADR 0024 §Administrative eligibility, read live: the principal is eligible while its own Personal
 * Profile is an unrestricted Adult.
 */
@Component
@RequiredArgsConstructor
class PrincipalEligibilityContributor implements FactContributor {

  static final String ELIGIBLE = "eligible";

  private final UserAccountRepository userAccountRepository;
  private final ProfileRepository profileRepository;

  @Override
  public FactRequirement provides() {
    return FactRequirement.PRINCIPAL_ELIGIBILITY;
  }

  @Override
  public void contribute(
      AuthenticatedIdentity identity, AuthorizationCheck check, EntitySlice slice) {
    var eligible =
        userAccountRepository
            .findById(identity.accountId())
            .flatMap(account -> profileRepository.findById(account.getPersonalProfileId()))
            .map(personal -> !personal.isRestricted())
            .orElse(false);
    slice.principalAttribute(ELIGIBLE, new PrimBool(eligible));
  }
}
