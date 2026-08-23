package com.streamarr.server.services.authorization.cedar;

import com.cedarpolicy.value.PrimString;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Contributes live Household membership and role; token claims are never write authority. */
@Component
@RequiredArgsConstructor
class LivePrincipalHouseholdContributor implements FactContributor {

  static final String LIVE_HOUSEHOLD_ROLE = "liveHouseholdRole";
  static final String LIVE_MEMBERSHIP_HOUSEHOLD = "liveMembershipHousehold";

  private final UserAccountRepository userAccountRepository;

  @Override
  public FactRequirement provides() {
    return FactRequirement.LIVE_PRINCIPAL_HOUSEHOLD;
  }

  @Override
  public void contribute(
      AuthenticatedIdentity identity, AuthorizationCheck check, EntitySlice slice) {
    userAccountRepository
        .findById(identity.accountId())
        .ifPresent(
            account -> {
              var household = CedarIds.household(account.getHouseholdId());
              slice.principalAttribute(
                  LIVE_HOUSEHOLD_ROLE, new PrimString(account.getHouseholdRole().name()));
              slice.principalAttribute(LIVE_MEMBERSHIP_HOUSEHOLD, household);
              slice.reference(household);
            });
  }
}
