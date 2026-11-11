package com.streamarr.server.services.identity;

import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.identity.AdministrationQueryService.ProfileAdministrationDetails;
import com.streamarr.server.services.pagination.ListPage;
import com.streamarr.server.services.pagination.PageRequest;
import com.streamarr.server.services.pagination.Pager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reads behind the Profile management pages of Household settings and Profile settings (ADR
 * 0024 §Read authorization). A reader who may not read a list gets it empty.
 */
@Service
@RequiredArgsConstructor
public class ProfileManagementQueryService {

  private final AuthorizationService authorizationService;
  private final AdministrationQueryService administrationQueryService;
  private final ManagedProfilesList managedProfilesList;

  /**
   * A page of the Profiles the Account manages, in every Household, by name, then ID. Empty for a
   * device-bound session and for a disabled Account.
   */
  @Transactional(readOnly = true)
  public ListPage<ProfileAdministrationDetails> managedProfiles(
      AuthenticatedIdentity identity, PageRequest request) {
    if (!authorizationService.isAllowed(identity, new Intent.ViewManagedProfiles())) {
      return ListPage.empty();
    }

    return administrationQueryService.profileAdministrationDetails(
        Pager.page(managedProfilesList, identity.accountId(), request));
  }
}
