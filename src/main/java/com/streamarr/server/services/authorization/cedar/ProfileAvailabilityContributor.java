package com.streamarr.server.services.authorization.cedar;

import com.cedarpolicy.value.PrimBool;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.ProfileSafetyRule;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The resource Profile as available in the principal's context Household right now: whether it is
 * actively shared there, whether the Household's PIN safety rule locks it, and whether selecting it
 * requires its PIN.
 */
@Component
@RequiredArgsConstructor
class ProfileAvailabilityContributor implements FactContributor {

  static final String AVAILABLE_IN_CONTEXT = "availableInContext";
  static final String LOCKED = "locked";
  static final String PIN_REQUIRED = "pinRequired";

  private final ProfileRepository profileRepository;

  @Override
  public FactRequirement provides() {
    return FactRequirement.PROFILE_AVAILABILITY;
  }

  @Override
  public void contribute(
      AuthenticatedIdentity identity, AuthorizationCheck check, EntitySlice slice) {
    var profileId = check.resourceId();
    var available = profileRepository.findAvailableInHousehold(identity.contextHouseholdId());
    var resource =
        available.stream().filter(profile -> profile.getId().equals(profileId)).findFirst();
    var locked = ProfileSafetyRule.lockedProfiles(available);
    slice.resourceAttribute(AVAILABLE_IN_CONTEXT, new PrimBool(resource.isPresent()));
    slice.resourceAttribute(LOCKED, new PrimBool(locked.contains(profileId)));
    slice.resourceAttribute(
        PIN_REQUIRED,
        new PrimBool(resource.map(profile -> profile.hasEffectivePin()).orElse(false)));
  }
}
