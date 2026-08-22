package com.streamarr.server.services.authorization.cedar;

import com.cedarpolicy.value.PrimBool;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.repositories.auth.DeviceRegistrationRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The resource Device registration's Household relative to the principal: whether the principal is
 * that Household's live admin. A missing registration contributes nothing, and absent facts read as
 * denied.
 */
@Component
@RequiredArgsConstructor
class RegistrationContributor implements FactContributor {

  static final String PRINCIPAL_ADMIN_OF_HOUSEHOLD = "principalAdminOfHousehold";

  private final DeviceRegistrationRepository registrationRepository;
  private final UserAccountRepository userAccountRepository;

  @Override
  public FactRequirement provides() {
    return FactRequirement.REGISTRATION;
  }

  @Override
  public void contribute(
      AuthenticatedIdentity identity, AuthorizationCheck check, EntitySlice slice) {
    var registration = registrationRepository.findById(check.resourceId());
    if (registration.isEmpty() || registration.get().getHouseholdId() == null) {
      return;
    }

    var householdId = registration.get().getHouseholdId();
    var adminOfHousehold =
        userAccountRepository
            .findById(identity.accountId())
            .filter(account -> account.getHouseholdRole() == HouseholdRole.ADMIN)
            .map(account -> householdId.equals(account.getHouseholdId()))
            .orElse(false);
    slice.resourceAttribute(PRINCIPAL_ADMIN_OF_HOUSEHOLD, new PrimBool(adminOfHousehold));
  }
}
