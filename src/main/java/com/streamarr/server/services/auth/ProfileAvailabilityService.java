package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.ProfileAccessDeniedException;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
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

  private final ProfileHouseholdShareRepository shareRepository;
  private final ProfileRepository profileRepository;

  @Transactional(readOnly = true)
  public List<SelectableProfile> selectableProfiles(
      AuthenticatedIdentity identity, UUID activeProfileId) {
    return selectableProfiles(identity.householdId(), activeProfileId);
  }

  public List<SelectableProfile> selectableProfiles(UserAccount account, UUID activeProfileId) {
    return selectableProfiles(account.getHomeHouseholdId(), activeProfileId);
  }

  private List<SelectableProfile> selectableProfiles(UUID householdId, UUID activeProfileId) {
    var shares = shareRepository.findByHouseholdIdAndStatus(householdId, ProfileShareStatus.ACTIVE);
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

  @Transactional(readOnly = true, noRollbackFor = ProfileAccessDeniedException.class)
  public Profile requireSelectableProfile(AuthenticatedIdentity identity, UUID profileId) {
    return requireSelectableProfile(identity.householdId(), profileId);
  }

  public Profile requireSelectableProfile(UserAccount account, UUID profileId) {
    return requireSelectableProfile(account.getHomeHouseholdId(), profileId);
  }

  private Profile requireSelectableProfile(UUID householdId, UUID profileId) {
    var shared =
        shareRepository.existsByProfileIdAndHouseholdIdAndStatus(
            profileId, householdId, ProfileShareStatus.ACTIVE);
    if (!shared) {
      throw new ProfileAccessDeniedException();
    }

    return profileRepository.findById(profileId).orElseThrow(ProfileAccessDeniedException::new);
  }

  public record SelectableProfile(UUID id, String name, boolean active, boolean pinProtected) {}
}
