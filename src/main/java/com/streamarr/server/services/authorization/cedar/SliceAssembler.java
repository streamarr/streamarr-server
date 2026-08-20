package com.streamarr.server.services.authorization.cedar;

import com.cedarpolicy.value.PrimBool;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Builds the entity slice for a check from the signed identity and the contributors the action's
 * fact requirements name. Startup fails when a requirement has no contributor or two contributors
 * claim the same fact, so an action can never silently evaluate without its facts.
 */
@Component
class SliceAssembler {

  private final Map<FactRequirement, FactContributor> contributors =
      new EnumMap<>(FactRequirement.class);

  SliceAssembler(List<FactContributor> factContributors) {
    factContributors.forEach(this::register);
    Arrays.stream(Action.values()).forEach(this::requireContributors);
  }

  private void register(FactContributor contributor) {
    var replaced = contributors.put(contributor.provides(), contributor);
    if (replaced != null) {
      throw new IllegalStateException(
          "Two contributors provide " + contributor.provides() + ": one fact, one contributor");
    }
  }

  private void requireContributors(Action action) {
    action.facts().forEach(requirement -> requireContributor(action, requirement));
  }

  private void requireContributor(Action action, FactRequirement requirement) {
    if (!contributors.containsKey(requirement)) {
      throw new IllegalStateException(
          "Action " + action + " requires " + requirement + " but no contributor provides it");
    }
  }

  EntitySlice assemble(AuthenticatedIdentity identity, AuthorizationCheck check) {
    var slice = new EntitySlice(CedarIds.account(identity.accountId()), check.resource());
    // Stamped on every slice, not through a fact family: the device forbid must see the flag on
    // every action, and an absent fact would read as "not device-bound" — failing open.
    slice.principalAttribute(
        SignedPrincipalContextContributor.DEVICE_BOUND, new PrimBool(identity.deviceBound()));
    for (var requirement : check.action().facts()) {
      contributors.get(requirement).contribute(identity, check, slice);
    }
    return slice;
  }
}
