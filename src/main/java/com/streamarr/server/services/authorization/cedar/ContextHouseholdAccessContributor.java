package com.streamarr.server.services.authorization.cedar;

import com.cedarpolicy.value.PrimBool;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Whether the Account may still use its context Household: member, or visitor via its share. */
@Component
@RequiredArgsConstructor
class ContextHouseholdAccessContributor implements FactContributor {

  static final String CONTEXT_HOUSEHOLD_USABLE = "contextHouseholdUsable";

  private final UserAccountRepository userAccountRepository;

  @Override
  public FactRequirement provides() {
    return FactRequirement.CONTEXT_HOUSEHOLD_ACCESS;
  }

  @Override
  public void contribute(
      AuthenticatedIdentity identity, AuthorizationCheck check, EntitySlice slice) {
    slice.principalAttribute(
        CONTEXT_HOUSEHOLD_USABLE,
        new PrimBool(
            userAccountRepository.mayUseHousehold(
                identity.accountId(), identity.contextHouseholdId())));
  }
}
