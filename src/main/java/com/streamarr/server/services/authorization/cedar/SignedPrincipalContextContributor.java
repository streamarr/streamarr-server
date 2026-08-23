package com.streamarr.server.services.authorization.cedar;

import com.cedarpolicy.value.PrimString;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import org.springframework.stereotype.Component;

/**
 * The principal's signed snapshot (ADR 0024 §Stateless JWT snapshots): membership Household and
 * role, context Household, and selected Profile. ServerAdmin is never carried in the token; only
 * the live fact may grant authority.
 */
@Component
class SignedPrincipalContextContributor implements FactContributor {

  static final String HOUSEHOLD_ROLE = "householdRole";
  static final String MEMBERSHIP_HOUSEHOLD = "membershipHousehold";
  static final String CONTEXT_HOUSEHOLD = "contextHousehold";
  static final String SELECTED_PROFILE = "selectedProfile";

  @Override
  public FactRequirement provides() {
    return FactRequirement.SIGNED_PRINCIPAL_CONTEXT;
  }

  @Override
  public void contribute(
      AuthenticatedIdentity identity, AuthorizationCheck check, EntitySlice slice) {
    var membership = CedarIds.household(identity.householdId());
    var context = CedarIds.household(identity.contextHouseholdId());
    slice.principalAttribute(HOUSEHOLD_ROLE, new PrimString(identity.householdRole().name()));
    slice.principalAttribute(MEMBERSHIP_HOUSEHOLD, membership);
    slice.principalAttribute(CONTEXT_HOUSEHOLD, context);
    slice.reference(membership);
    slice.reference(context);
    if (identity.profileId() != null) {
      var selected = CedarIds.profile(identity.profileId());
      slice.principalAttribute(SELECTED_PROFILE, selected);
      slice.reference(selected);
    }
  }
}
