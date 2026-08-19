package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.SetupAlreadyCompletedException;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.ServerBootstrapRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.repositories.streaming.SessionProgressRepository;
import com.streamarr.server.repositories.streaming.WatchHistoryRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Initial setup is the one provisioning exception outside Cedar (ADR 0025): there is no Account
 * principal yet, so it atomically claims the "no Account exists" condition (the server_bootstrap
 * row) and creates the first Household, Personal Profile, Account, and structural share in that
 * claim. The first Account becomes HouseholdAdmin and ServerAdmin. No synthetic bootstrap principal
 * exists.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SetupService {

  /**
   * Identity every watch row was written against before real profiles existed. Setup remaps those
   * rows to the first profile; the constant disappears when the V047 profile FK is validated and
   * the placeholder era ends.
   */
  private static final UUID LEGACY_PLACEHOLDER_PROFILE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");

  private static final String DEFAULT_RATING_REGION = "US";

  private static final String EMAIL_UNIQUE_INDEX = "uq_user_account_email";

  private final UserAccountRepository userAccountRepository;
  private final HouseholdRepository householdRepository;
  private final ProfileRepository profileRepository;
  private final ProfileHouseholdShareRepository shareRepository;
  private final ServerBootstrapRepository serverBootstrapRepository;
  private final SessionProgressRepository sessionProgressRepository;
  private final WatchHistoryRepository watchHistoryRepository;
  private final PasswordEncoder passwordEncoder;

  public boolean isSetupComplete() {
    return serverBootstrapRepository.isClaimed();
  }

  @Transactional
  public SetupResult setup(SetupCommand command) {
    // Fast-path guard only — the atomic claim below stays the arbiter. Without it, every
    // post-setup call burns full Argon2 work and a flushed insert before failing.
    if (serverBootstrapRepository.isClaimed()) {
      throw new SetupAlreadyCompletedException();
    }

    var household =
        householdRepository.saveAndFlush(
            Household.builder()
                .name(command.householdName())
                .defaultRatingRegion(DEFAULT_RATING_REGION)
                .build());

    var profile =
        profileRepository.saveAndFlush(
            Profile.builder()
                .householdId(household.getId())
                .name(command.profileName())
                .kind(ProfileKind.ADULT)
                .build());

    // saveAndFlush before the jOOQ claim: Hibernate defers JPA inserts until flush, but the
    // claim runs as direct SQL against the Account row's foreign key.
    var admin = createAdminAccount(command, household, profile);

    if (!serverBootstrapRepository.claim(admin.getId())) {
      throw new SetupAlreadyCompletedException();
    }

    shareRepository.saveAndFlush(
        ProfileHouseholdShare.builder()
            .profileId(profile.getId())
            .householdId(household.getId())
            .status(ProfileShareStatus.ACTIVE)
            .structural(true)
            .build());

    sessionProgressRepository.reassignProfile(LEGACY_PLACEHOLDER_PROFILE_ID, profile.getId());
    watchHistoryRepository.reassignProfile(LEGACY_PLACEHOLDER_PROFILE_ID, profile.getId());

    return SetupResult.builder().admin(admin).household(household).profile(profile).build();
  }

  private UserAccount createAdminAccount(
      SetupCommand command, Household household, Profile profile) {
    // Accounts only exist once setup has won, so a duplicate email before the claim can only
    // be a competing setup that already flushed — report the domain conflict, not the
    // constraint violation. Any other integrity failure is a real defect and must surface.
    try {
      return userAccountRepository.saveAndFlush(
          UserAccount.builder()
              .email(command.email())
              .displayName(command.displayName())
              .passwordHash(passwordEncoder.encode(command.password()))
              .serverAdmin(true)
              .householdId(household.getId())
              .householdRole(HouseholdRole.ADMIN)
              .personalProfileId(profile.getId())
              .enabled(true)
              .build());
    } catch (DataIntegrityViolationException e) {
      if (!isDuplicateAdminEmail(e)) {
        throw e;
      }
      log.warn("Setup lost the admin-email race to a competing setup.", e);
      throw new SetupAlreadyCompletedException(e);
    }
  }

  private static boolean isDuplicateAdminEmail(DataIntegrityViolationException e) {
    return e.getCause() instanceof ConstraintViolationException violation
        && EMAIL_UNIQUE_INDEX.equals(violation.getConstraintName());
  }
}
