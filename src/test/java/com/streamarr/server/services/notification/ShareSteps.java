package com.streamarr.server.services.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.identity.ProfileSharingService;
import com.streamarr.server.services.identity.ShareCodeCommand;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupport.TestIdentity;
import java.time.Instant;
import lombok.Builder;
import lombok.NonNull;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The share writes and the parties that the Notification tests arrange: offers and answers go
 * through the sharing service, as a person makes them, so each makes its Notifications.
 */
@Builder
final class ShareSteps {

  static final String IP_ADDRESS = "203.0.113.7";

  @NonNull private final ProfileSharingService sharing;
  @NonNull private final AuthTestSupport auth;
  @NonNull private final ProfileRepository profiles;
  @NonNull private final ProfileManagerRepository managers;
  @NonNull private final ProfileHouseholdShareRepository shares;
  @NonNull private final UserAccountRepository accounts;
  @NonNull private final TransactionTemplate transactions;

  ProfileHouseholdShare offer(TestIdentity offerer, Profile profile, TestIdentity target) {
    var code = auth.shareCodeFor(target);
    return sharing
        .offerProfileShare(
            auth.identityOf(offerer),
            profile.getId(),
            ShareCodeCommand.builder().code(code).ipAddress(IP_ADDRESS).build())
        .fold(
            share -> share,
            rejection -> {
              throw new IllegalStateException("The offer was refused: " + rejection);
            });
  }

  ProfileHouseholdShare accept(TestIdentity answerer, ProfileHouseholdShare offer) {
    return sharing
        .acceptProfileShare(auth.identityOf(answerer), offer.getId())
        .fold(
            share -> share,
            rejection -> {
              throw new IllegalStateException("The offer was not accepted: " + rejection);
            });
  }

  /** Moves the offer's expiry into the past, as if its time ran out. */
  void lapse(ProfileHouseholdShare offer) {
    transactions.executeWithoutResult(
        _ -> {
          var lapsed = shares.findById(offer.getId()).orElseThrow();
          lapsed.setExpiresAt(Instant.now().minusSeconds(1));
          shares.saveAndFlush(lapsed);
        });
  }

  /** A Profile of the owner's Household, available there, with the given direct managers. */
  Profile profileManagedBy(String name, TestIdentity owner, TestIdentity... directManagers) {
    return transactions.execute(
        _ -> {
          var profile =
              profiles.saveAndFlush(
                  ProfileFixture.defaultProfileBuilder()
                      .name(name)
                      .householdId(owner.household().getId())
                      .build());
          for (var manager : directManagers) {
            managers.saveAndFlush(
                ProfileManager.builder()
                    .accountId(manager.account().getId())
                    .profileId(profile.getId())
                    .build());
          }

          shares.saveAndFlush(
              ProfileHouseholdShare.builder()
                  .profileId(profile.getId())
                  .householdId(owner.household().getId())
                  .status(ProfileShareStatus.ACTIVE)
                  .build());
          return profile;
        });
  }

  /** The member, promoted to HouseholdAdmin of its Household. */
  TestIdentity promoted(TestIdentity member) {
    transactions.executeWithoutResult(
        _ -> accounts.tryPromoteToHouseholdAdmin(member.account().getId()));
    var promoted = accounts.findById(member.account().getId()).orElseThrow();
    assertThat(promoted.getHouseholdRole()).isEqualTo(HouseholdRole.ADMIN);
    return TestIdentity.builder()
        .account(promoted)
        .household(member.household())
        .profile(member.profile())
        .session(member.session())
        .rawRefreshToken(member.rawRefreshToken())
        .build();
  }
}
