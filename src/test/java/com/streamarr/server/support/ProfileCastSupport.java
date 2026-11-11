package com.streamarr.server.support;

import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.support.AuthTestSupport.TestIdentity;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Arranges who is what to a Profile, as ADR 0024's relationships store it: Profiles and their
 * managers, the Households a Profile is available in, and the HouseholdAdmins and supervised people
 * of a Household. Deleting the identity of a Household's first Account deletes what this arranged
 * there.
 */
@RequiredArgsConstructor
public class ProfileCastSupport {

  private final AuthTestSupport authTestSupport;
  private final ProfileRepository profileRepository;
  private final ProfileManagerRepository profileManagerRepository;
  private final ProfileHouseholdShareRepository shareRepository;
  private final UserAccountRepository userAccountRepository;
  private final TransactionTemplate transactionTemplate;

  /**
   * A Profile that belongs to the manager's Household and is available there, with the manager's
   * direct grant.
   */
  public Profile profileManagedBy(TestIdentity manager, Profile.ProfileBuilder<?, ?> profile) {
    return transactionTemplate.execute(
        _ -> {
          var saved =
              profileRepository.saveAndFlush(
                  profile.householdId(manager.household().getId()).build());
          saveGrant(manager, saved);
          shareRepository.saveAndFlush(
              ProfileHouseholdShare.builder()
                  .profileId(saved.getId())
                  .householdId(manager.household().getId())
                  .status(ProfileShareStatus.ACTIVE)
                  .structural(true)
                  .build());
          return saved;
        });
  }

  /**
   * A Profile that belongs to the manager's Household, with the manager's direct grant, and is
   * available nowhere, as a Profile is once a HouseholdAdmin removes it from its Household.
   */
  public Profile unavailableProfileManagedBy(
      TestIdentity manager, Profile.ProfileBuilder<?, ?> profile) {
    return transactionTemplate.execute(
        _ -> {
          var saved =
              profileRepository.saveAndFlush(
                  profile.householdId(manager.household().getId()).build());
          saveGrant(manager, saved);
          return saved;
        });
  }

  /** Makes the Profile available in the host's Household, as an accepted offer does. */
  public void makeAvailable(Profile profile, TestIdentity host) {
    transactionTemplate.executeWithoutResult(
        _ ->
            shareRepository.saveAndFlush(
                ProfileHouseholdShare.builder()
                    .profileId(profile.getId())
                    .householdId(host.household().getId())
                    .status(ProfileShareStatus.ACTIVE)
                    .build()));
  }

  /** Gives the manager a direct grant for the Profile. */
  public void grant(TestIdentity manager, Profile profile) {
    transactionTemplate.executeWithoutResult(_ -> saveGrant(manager, profile));
  }

  /** Another HouseholdAdmin of the identity's Household, whose Personal Profile is an Adult. */
  public TestIdentity secondAdminOf(TestIdentity householdOf) {
    var admin = authTestSupport.createHouseholdMemberIdentity(householdOf);
    transactionTemplate.executeWithoutResult(
        _ -> {
          var account = userAccountRepository.findById(admin.account().getId()).orElseThrow();
          account.setHouseholdRole(HouseholdRole.ADMIN);
          userAccountRepository.saveAndFlush(account);
        });
    return admin;
  }

  /**
   * A member of the HouseholdAdmin's Household whose Personal Profile has a content limit, so that
   * the HouseholdAdmin manages it.
   */
  public TestIdentity supervisedMemberOf(TestIdentity householdAdmin) {
    var member = authTestSupport.createHouseholdMemberIdentity(householdAdmin);
    transactionTemplate.executeWithoutResult(
        _ -> {
          saveGrant(householdAdmin, member.profile());
          var profile = profileRepository.findById(member.profile().getId()).orElseThrow();
          profile.setMaximumAllowedRatingAge(12);
          profileRepository.saveAndFlush(profile);
        });
    return member;
  }

  private void saveGrant(TestIdentity manager, Profile profile) {
    profileManagerRepository.saveAndFlush(
        ProfileManager.builder()
            .accountId(manager.account().getId())
            .profileId(profile.getId())
            .build());
  }
}
