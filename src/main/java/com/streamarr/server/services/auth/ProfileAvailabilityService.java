package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.ProfileAccessDeniedException;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProfileAvailabilityService {

  private final UserAccountRepository accountRepository;
  private final ProfileHouseholdShareRepository shareRepository;
  private final ProfileRepository profileRepository;

  @Transactional(readOnly = true)
  public List<SelectableProfile> selectableProfiles(UUID accountId, UUID activeProfileId) {
    var account = loadAccount(accountId);

    return shareRepository
        .findByHouseholdIdAndStatus(account.getHomeHouseholdId(), ProfileShareStatus.ACTIVE)
        .stream()
        .map(share -> profileRepository.findById(share.getProfileId()))
        .flatMap(java.util.Optional::stream)
        .map(
            profile ->
                new SelectableProfile(
                    profile.getId(), profile.getName(), profile.getId().equals(activeProfileId)))
        .toList();
  }

  public Profile requireSelectableProfile(UUID accountId, UUID profileId) {
    var account = loadAccount(accountId);
    var shared =
        shareRepository
            .findByHouseholdIdAndStatus(account.getHomeHouseholdId(), ProfileShareStatus.ACTIVE)
            .stream()
            .anyMatch(share -> share.getProfileId().equals(profileId));
    if (!shared) {
      throw new ProfileAccessDeniedException();
    }

    return profileRepository.findById(profileId).orElseThrow(ProfileAccessDeniedException::new);
  }

  private UserAccount loadAccount(UUID accountId) {
    return accountRepository.findById(accountId).orElseThrow(AuthenticationRequiredException::new);
  }

  public record SelectableProfile(UUID id, String name, boolean active) {}
}
