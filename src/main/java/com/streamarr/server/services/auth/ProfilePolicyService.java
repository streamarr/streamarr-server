package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileClassification;
import com.streamarr.server.domain.auth.SecurityAuditOperation;
import com.streamarr.server.exceptions.ProfileAccessDeniedException;
import com.streamarr.server.exceptions.ProfileManagementDeniedException;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProfilePolicyService {

  private final ProfileRepository profileRepository;
  private final ProfileManagerRepository managerRepository;
  private final ProfileHouseholdShareRepository shareRepository;
  private final HouseholdProfileSafetyService safetyService;
  private final KidProfileManagerPolicy kidManagerPolicy;
  private final SecurityAuditService auditService;

  @Transactional
  public void changePolicy(ProfilePolicyChange change) {
    if (!managerRepository.existsByAccountIdAndProfileId(
        change.actingAccountId(), change.profileId())) {
      throw new ProfileManagementDeniedException();
    }

    var profile =
        profileRepository
            .findById(change.profileId())
            .orElseThrow(ProfileAccessDeniedException::new);
    var maximumAllowedRatingAge = resolveMaximumAllowedRatingAge(change, profile);
    var pinHash = resolvePinHash(change, profile);
    var proposed =
        profile.toBuilder()
            .classification(change.classification())
            .maximumAllowedRatingAge(maximumAllowedRatingAge)
            .pinHash(pinHash)
            .build();
    safetyService.validatePolicyChange(proposed);
    if (change.classification() == ProfileClassification.KID) {
      kidManagerPolicy.validateKidClassification(change.profileId());
    }

    profile.setClassification(change.classification());
    profile.setMaximumAllowedRatingAge(maximumAllowedRatingAge);
    profile.setPinHash(pinHash);
    profileRepository.save(profile);
    auditService.recordEvent(
        SecurityAuditRecord.builder()
            .actingAccountId(change.actingAccountId())
            .targetProfileId(change.profileId())
            .operation(SecurityAuditOperation.PROFILE_POLICY_CHANGED)
            .build());
  }

  private Integer resolveMaximumAllowedRatingAge(ProfilePolicyChange change, Profile profile) {
    if (change.clearMaximumAllowedRatingAge()) {
      return null;
    }
    return change.maximumAllowedRatingAge() == null
        ? profile.getMaximumAllowedRatingAge()
        : change.maximumAllowedRatingAge();
  }

  private String resolvePinHash(ProfilePolicyChange change, Profile profile) {
    if (change.clearPin()) {
      return null;
    }
    return change.pinHash() == null ? profile.getPinHash() : change.pinHash();
  }
}
