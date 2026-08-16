package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
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
public class ProfileSharingService {

  private final ProfileManagerRepository managerRepository;
  private final ProfileHouseholdShareRepository shareRepository;
  private final ProfileRepository profileRepository;
  private final ProfileManagementService managementService;
  private final HouseholdProfileSafetyService safetyService;
  private final ProfileSelectionCleaner selectionCleaner;
  private final KidProfileManagerPolicy kidManagerPolicy;
  private final SecurityAuditService auditService;

  @Transactional
  public ProfileHouseholdShare offer(ProfileShareOffer offer) {
    if (!managerRepository.existsByAccountIdAndProfileId(
        offer.actingAccountId(), offer.profileId())) {
      throw new ProfileManagementDeniedException();
    }

    var result =
        shareRepository.insertPendingIfAbsent(offer.profileId(), offer.targetHouseholdId());
    if (result.inserted()) {
      auditService.recordEvent(
          SecurityAuditRecord.builder()
              .actingAccountId(offer.actingAccountId())
              .targetHouseholdId(offer.targetHouseholdId())
              .targetProfileId(offer.profileId())
              .operation(SecurityAuditOperation.PROFILE_SHARE_OFFERED)
              .build());
    }
    return result.share();
  }

  @Transactional
  public ProfileHouseholdShare accept(ProfileShareAcceptance acceptance) {
    var share =
        shareRepository
            .findById(acceptance.shareId())
            .orElseThrow(ProfileAccessDeniedException::new);
    if (!acceptance.authority().hasHouseholdRole(share.getHouseholdId(), HouseholdRole.PARENT)
        || share.getStatus() != ProfileShareStatus.PENDING) {
      throw new ProfileAccessDeniedException();
    }

    var profile =
        profileRepository
            .findById(share.getProfileId())
            .orElseThrow(ProfileAccessDeniedException::new);
    if (acceptance.managementInvitationId() != null) {
      managementService.acceptForProfile(
          ProfileManagerInvitationAcceptance.builder()
              .actingAccountId(acceptance.actingAccountId())
              .invitationId(acceptance.managementInvitationId())
              .build(),
          profile.getId());
    }
    kidManagerPolicy.validateShareActivation(profile.getId(), share.getHouseholdId());

    safetyService.validateActivation(profile, share.getHouseholdId());
    var acceptedShare =
        shareRepository
            .activatePending(share.getId())
            .orElseThrow(ProfileAccessDeniedException::new);
    auditService.recordEvent(
        SecurityAuditRecord.builder()
            .actingAccountId(acceptance.actingAccountId())
            .targetHouseholdId(share.getHouseholdId())
            .targetProfileId(share.getProfileId())
            .operation(SecurityAuditOperation.PROFILE_SHARE_ACCEPTED)
            .build());
    return acceptedShare;
  }

  @Transactional
  public void removeFromHousehold(HouseholdProfileRemoval removal) {
    var share =
        shareRepository.findById(removal.shareId()).orElseThrow(ProfileAccessDeniedException::new);
    if (!removal.authority().hasHouseholdRole(share.getHouseholdId(), HouseholdRole.PARENT)
        || share.getStatus() != ProfileShareStatus.ACTIVE) {
      throw new ProfileAccessDeniedException();
    }

    shareRepository.delete(share);
    selectionCleaner.clear(share.getProfileId(), share.getHouseholdId());
    auditService.recordEvent(
        SecurityAuditRecord.builder()
            .actingAccountId(removal.actingAccountId())
            .targetHouseholdId(share.getHouseholdId())
            .targetProfileId(share.getProfileId())
            .operation(SecurityAuditOperation.PROFILE_UNSHARED_BY_HOUSEHOLD)
            .build());
  }

  @Transactional
  public void reject(ProfileShareRejection rejection) {
    var share =
        shareRepository
            .findById(rejection.shareId())
            .orElseThrow(ProfileAccessDeniedException::new);
    if (share.getStatus() != ProfileShareStatus.PENDING
        || !rejection.authority().hasHouseholdRole(share.getHouseholdId(), HouseholdRole.PARENT)) {
      throw new ProfileAccessDeniedException();
    }

    var rejectedShare =
        shareRepository.deletePending(share.getId()).orElseThrow(ProfileAccessDeniedException::new);
    auditService.recordEvent(
        SecurityAuditRecord.builder()
            .actingAccountId(rejection.actingAccountId())
            .targetHouseholdId(rejectedShare.getHouseholdId())
            .targetProfileId(rejectedShare.getProfileId())
            .operation(SecurityAuditOperation.PROFILE_SHARE_REJECTED)
            .build());
  }

  @Transactional
  public void cancel(ProfileShareCancellation cancellation) {
    var share =
        shareRepository
            .findById(cancellation.shareId())
            .filter(candidate -> candidate.getStatus() == ProfileShareStatus.PENDING)
            .orElseThrow(ProfileAccessDeniedException::new);
    if (!managerRepository.existsByAccountIdAndProfileId(
        cancellation.actingAccountId(), share.getProfileId())) {
      throw new ProfileManagementDeniedException();
    }

    var canceledShare =
        shareRepository.deletePending(share.getId()).orElseThrow(ProfileAccessDeniedException::new);
    auditService.recordEvent(
        SecurityAuditRecord.builder()
            .actingAccountId(cancellation.actingAccountId())
            .targetHouseholdId(canceledShare.getHouseholdId())
            .targetProfileId(canceledShare.getProfileId())
            .operation(SecurityAuditOperation.PROFILE_SHARE_CANCELED)
            .build());
  }

  @Transactional
  public void leaveCurrentHome(ProfileHomeDeparture departure) {
    var share =
        shareRepository
            .findByProfileIdAndHouseholdId(
                departure.activeProfileId(), departure.authority().householdId())
            .filter(candidate -> candidate.getStatus() == ProfileShareStatus.ACTIVE)
            .orElseThrow(ProfileAccessDeniedException::new);

    shareRepository.delete(share);
    selectionCleaner.clear(share.getProfileId(), share.getHouseholdId());
    auditService.recordEvent(
        SecurityAuditRecord.builder()
            .actingAccountId(departure.actingAccountId())
            .targetHouseholdId(share.getHouseholdId())
            .targetProfileId(share.getProfileId())
            .operation(SecurityAuditOperation.PROFILE_LEFT_HOME)
            .build());
  }
}
