package com.streamarr.server.services.authorization.cedar;

import com.streamarr.server.services.auth.AuthenticatedIdentity;
import java.util.ArrayList;
import java.util.List;

/** A contributor per fact requirement so the assembler's parity check passes in unit tests. */
final class ContributorStubs {

  private ContributorStubs() {}

  /** Every requirement contributes nothing, except the overrides, which replace their stub. */
  static List<FactContributor> allWith(FactContributor... overrides) {
    var contributors = new ArrayList<FactContributor>();
    for (var requirement : FactRequirement.values()) {
      var override =
          List.of(overrides).stream().filter(c -> c.provides() == requirement).findFirst();
      contributors.add(override.orElseGet(() -> noop(requirement)));
    }

    return contributors;
  }

  static FactContributor noop(FactRequirement requirement) {
    return new FactContributor() {
      @Override
      public FactRequirement provides() {
        return requirement;
      }

      @Override
      public void contribute(
          AuthenticatedIdentity identity, AuthorizationCheck check, EntitySlice slice) {
        // nothing to contribute
      }
    };
  }
}
