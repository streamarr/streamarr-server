package com.streamarr.server.services.authorization.cedar;

import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** The resource Account's membership Household, read live. */
@Component
@RequiredArgsConstructor
class AccountHouseholdContributor implements FactContributor {

  static final String MEMBERSHIP_HOUSEHOLD = "membershipHousehold";

  private final UserAccountRepository userAccountRepository;

  @Override
  public FactRequirement provides() {
    return FactRequirement.ACCOUNT_HOUSEHOLD;
  }

  @Override
  public void contribute(
      AuthenticatedIdentity identity, AuthorizationCheck check, EntitySlice slice) {
    userAccountRepository
        .findById(check.resourceId())
        .ifPresent(
            account -> {
              var household = CedarIds.household(account.getHouseholdId());
              slice.resourceAttribute(MEMBERSHIP_HOUSEHOLD, household);
              slice.reference(household);
            });
  }
}
