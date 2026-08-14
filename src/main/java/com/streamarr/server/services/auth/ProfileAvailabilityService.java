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
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
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
    var shares =
        shareRepository.findByHouseholdIdAndStatus(
            account.getHomeHouseholdId(), ProfileShareStatus.ACTIVE);
    var profileIds = shares.stream().map(share -> share.getProfileId()).toList();
    var profilesById =
        profileRepository.findAllById(profileIds).stream()
            .collect(Collectors.toMap(Profile::getId, Function.identity()));

    return shares.stream()
        .map(share -> profilesById.get(share.getProfileId()))
        .filter(Objects::nonNull)
        .map(
            profile ->
                new SelectableProfile(
                    profile.getId(),
                    profile.getName(),
                    profile.getId().equals(activeProfileId),
                    profile.getPinHash() != null && !profile.getPinHash().isBlank()))
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

  public record SelectableProfile(UUID id, String name, boolean active, boolean pinProtected) {}
}
