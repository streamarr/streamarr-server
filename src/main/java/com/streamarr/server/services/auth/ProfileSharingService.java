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
import com.streamarr.server.repositories.auth.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProfileSharingService {

  private final ProfileManagerRepository managerRepository;
  private final ProfileHouseholdShareRepository shareRepository;
  private final UserAccountRepository accountRepository;
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

    var share = shareRepository.insertPendingIfAbsent(offer.profileId(), offer.targetHouseholdId());
    auditService.recordEvent(
        SecurityAuditRecord.builder()
            .actingAccountId(offer.actingAccountId())
            .targetHouseholdId(offer.targetHouseholdId())
            .targetProfileId(offer.profileId())
            .operation(SecurityAuditOperation.PROFILE_SHARE_OFFERED)
            .build());
    return share;
  }

  @Transactional
  public ProfileHouseholdShare accept(ProfileShareAcceptance acceptance) {
    var share =
        shareRepository
            .findById(acceptance.shareId())
            .orElseThrow(ProfileAccessDeniedException::new);
    var account =
        accountRepository
            .findById(acceptance.actingAccountId())
            .orElseThrow(ProfileAccessDeniedException::new);

    if (!account.getHomeHouseholdId().equals(share.getHouseholdId())
        || !canAdministerHousehold(account.getHouseholdRole())
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
    share.setStatus(ProfileShareStatus.ACTIVE);
    var acceptedShare = shareRepository.save(share);
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
    var account =
        accountRepository
            .findById(removal.actingAccountId())
            .orElseThrow(ProfileAccessDeniedException::new);

    if (!account.getHomeHouseholdId().equals(share.getHouseholdId())
        || !canAdministerHousehold(account.getHouseholdRole())
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
    var account =
        accountRepository
            .findById(rejection.actingAccountId())
            .orElseThrow(ProfileAccessDeniedException::new);
    if (share.getStatus() != ProfileShareStatus.PENDING
        || !share.getHouseholdId().equals(account.getHomeHouseholdId())
        || !canAdministerHousehold(account.getHouseholdRole())) {
      throw new ProfileAccessDeniedException();
    }

    shareRepository.delete(share);
    auditService.recordEvent(
        SecurityAuditRecord.builder()
            .actingAccountId(rejection.actingAccountId())
            .targetHouseholdId(share.getHouseholdId())
            .targetProfileId(share.getProfileId())
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

    shareRepository.delete(share);
    auditService.recordEvent(
        SecurityAuditRecord.builder()
            .actingAccountId(cancellation.actingAccountId())
            .targetHouseholdId(share.getHouseholdId())
            .targetProfileId(share.getProfileId())
            .operation(SecurityAuditOperation.PROFILE_SHARE_CANCELED)
            .build());
  }

  @Transactional
  public void leaveCurrentHome(ProfileHomeDeparture departure) {
    var account =
        accountRepository
            .findById(departure.actingAccountId())
            .orElseThrow(ProfileAccessDeniedException::new);
    var share =
        shareRepository
            .findByProfileIdAndHouseholdId(
                departure.activeProfileId(), account.getHomeHouseholdId())
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

  private boolean canAdministerHousehold(HouseholdRole householdRole) {
    return householdRole == HouseholdRole.OWNER || householdRole == HouseholdRole.PARENT;
  }
}
