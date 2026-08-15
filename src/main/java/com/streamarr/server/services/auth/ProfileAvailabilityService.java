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

  /**
   * Lists profiles available to the account, preserving household share order.
   *
   * @param accountId       the account whose available profiles are requested
   * @param activeProfileId the profile currently active for the account
   * @return selectable profile metadata, including active and PIN-protection status
   */
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

  /**
   * Authorizes access to a profile shared with the account's home household.
   *
   * @param accountId the account requesting access
   * @param profileId the profile to authorize
   * @return the authorized profile
   */
  @Transactional(readOnly = true, noRollbackFor = ProfileAccessDeniedException.class)
  public Profile requireSelectableProfile(UUID accountId, UUID profileId) {
    var account = loadAccount(accountId);
    var shared =
        shareRepository.existsByProfileIdAndHouseholdIdAndStatus(
            profileId, account.getHomeHouseholdId(), ProfileShareStatus.ACTIVE);
    if (!shared) {
      throw new ProfileAccessDeniedException();
    }

    return profileRepository.findById(profileId).orElseThrow(ProfileAccessDeniedException::new);
  }

  /**
   * Loads the account identified by the given ID.
   *
   * @param accountId the account identifier
   * @return the matching account
   * @throws AuthenticationRequiredException if no account exists for the ID
   */
  private UserAccount loadAccount(UUID accountId) {
    return accountRepository.findById(accountId).orElseThrow(AuthenticationRequiredException::new);
  }

  public record SelectableProfile(UUID id, String name, boolean active, boolean pinProtected) {}
}
