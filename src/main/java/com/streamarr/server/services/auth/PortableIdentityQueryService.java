package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerInvitationRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PortableIdentityQueryService {

  private final UserAccountRepository accountRepository;
  private final ProfileManagerRepository managerRepository;
  private final ProfileManagerInvitationRepository invitationRepository;
  private final ProfileHouseholdShareRepository shareRepository;

  @Transactional(readOnly = true)
  public List<ProfileHouseholdShare> shares(AuthenticatedIdentity identity) {
    var account = requireAccount(identity);
    var managedProfileIds = managedProfileIds(account.getId());
    var managedShares = shareRepository.findByProfileIdIn(managedProfileIds);
    if (!canAdministerHousehold(account.getHouseholdRole())) {
      return sortedShares(managedShares.stream());
    }
    return sortedShares(
        Stream.concat(
            managedShares.stream(),
            shareRepository.findByHouseholdId(account.getHomeHouseholdId()).stream()));
  }

  @Transactional(readOnly = true)
  public List<ProfileManagerInvitation> invitations(AuthenticatedIdentity identity) {
    var account = requireAccount(identity);
    return Stream.concat(
            invitationRepository.findByInvitedAccountId(account.getId()).stream(),
            invitationRepository.findByProfileIdIn(managedProfileIds(account.getId())).stream())
        .distinct()
        .sorted(Comparator.comparing(ProfileManagerInvitation::getId))
        .toList();
  }

  @Transactional(readOnly = true)
  public List<ProfileManager> managers(AuthenticatedIdentity identity) {
    var account = requireAccount(identity);
    var accessibleProfileIds = managedProfileIds(account.getId());
    if (canAdministerHousehold(account.getHouseholdRole())) {
      shareRepository.findByHouseholdId(account.getHomeHouseholdId()).stream()
          .map(ProfileHouseholdShare::getProfileId)
          .forEach(accessibleProfileIds::add);
    }
    return managerRepository.findByProfileIdIn(accessibleProfileIds).stream()
        .sorted(Comparator.comparing(ProfileManager::getId))
        .toList();
  }

  private UserAccount requireAccount(AuthenticatedIdentity identity) {
    return accountRepository
        .findById(identity.accountId())
        .filter(candidate -> candidate.isEnabled())
        .orElseThrow(AuthenticationRequiredException::new);
  }

  private Set<UUID> managedProfileIds(UUID accountId) {
    return new HashSet<>(
        managerRepository.findByAccountId(accountId).stream()
            .map(ProfileManager::getProfileId)
            .toList());
  }

  private List<ProfileHouseholdShare> sortedShares(Stream<ProfileHouseholdShare> shares) {
    return shares.distinct().sorted(Comparator.comparing(ProfileHouseholdShare::getId)).toList();
  }

  private boolean canAdministerHousehold(HouseholdRole role) {
    return role == HouseholdRole.OWNER || role == HouseholdRole.PARENT;
  }
}
