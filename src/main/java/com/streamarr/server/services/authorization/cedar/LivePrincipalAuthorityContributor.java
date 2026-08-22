package com.streamarr.server.services.authorization.cedar;

import com.cedarpolicy.value.PrimBool;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Loads {@code enabled} and {@code serverAdmin} from PostgreSQL for the actions that need live
 * authority. A missing Account contributes nothing, and policy reads an absent fact as "not
 * proven"; the token's ServerAdmin claim is never consulted.
 */
@Component
@RequiredArgsConstructor
class LivePrincipalAuthorityContributor implements FactContributor {

  static final String ENABLED = "enabled";
  static final String SERVER_ADMIN = "serverAdmin";

  private final UserAccountRepository userAccountRepository;

  @Override
  public FactRequirement provides() {
    return FactRequirement.LIVE_PRINCIPAL_AUTHORITY;
  }

  @Override
  public void contribute(AuthenticatedIdentity identity, EntitySlice slice) {
    userAccountRepository
        .findAuthorityFacts(identity.accountId())
        .ifPresent(
            facts -> {
              slice.principalAttribute(ENABLED, new PrimBool(facts.enabled()));
              slice.principalAttribute(SERVER_ADMIN, new PrimBool(facts.serverAdmin()));
            });
  }
}
