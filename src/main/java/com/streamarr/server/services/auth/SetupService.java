package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.SetupAlreadyCompletedException;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
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
  private final ProfileManagerRepository profileManagerRepository;
  private final ProfileHouseholdShareRepository profileShareRepository;
  private final ServerBootstrapRepository serverBootstrapRepository;
  private final SessionProgressRepository sessionProgressRepository;
  private final WatchHistoryRepository watchHistoryRepository;
  private final PasswordEncoder passwordEncoder;

  public boolean isSetupComplete() {
    return serverBootstrapRepository.isClaimed();
  }

  /**
   * Initializes the server with its first household, administrator account, and profile.
   *
   * @param command the household, administrator, and profile details
   * @return the created administrator account, household, and profile
   * @throws SetupAlreadyCompletedException if setup has already been claimed
   */
  @Transactional
  public SetupResult setup(SetupCommand command) {
    // Fast-path guard only — the atomic claim below stays the arbiter. Without it, every
    // post-setup call burns full Argon2 work and a flushed insert before failing.
    if (serverBootstrapRepository.isClaimed()) {
      throw new SetupAlreadyCompletedException();
    }

    // The household and account must exist before the atomic database claim references them.
    var household =
        householdRepository.save(
            Household.builder()
                .name(command.householdName())
                .defaultRatingRegion(DEFAULT_RATING_REGION)
                .build());

    var admin = createAdminAccount(command, household.getId());

    if (!serverBootstrapRepository.claim(admin.getId())) {
      throw new SetupAlreadyCompletedException();
    }

    var profile =
        profileRepository.saveAndFlush(Profile.builder().name(command.profileName()).build());

    profileManagerRepository.save(
        ProfileManager.builder().accountId(admin.getId()).profileId(profile.getId()).build());
    profileShareRepository.save(
        ProfileHouseholdShare.builder()
            .profileId(profile.getId())
            .householdId(household.getId())
            .status(ProfileShareStatus.ACTIVE)
            .build());

    sessionProgressRepository.reassignProfile(LEGACY_PLACEHOLDER_PROFILE_ID, profile.getId());
    watchHistoryRepository.reassignProfile(LEGACY_PLACEHOLDER_PROFILE_ID, profile.getId());

    return SetupResult.builder().admin(admin).household(household).profile(profile).build();
  }

  /**
   * Creates an enabled owner-level administrator account for the specified household.
   *
   * @param command         setup data containing the administrator's email, display name, and password
   * @param homeHouseholdId identifier of the administrator's home household
   * @return                the persisted administrator account
   * @throws SetupAlreadyCompletedException if another setup operation already claimed the administrator email
   */
  private UserAccount createAdminAccount(SetupCommand command, UUID homeHouseholdId) {
    // Accounts only exist once setup has won, so a duplicate email before the claim can only
    // be a competing setup that already flushed — report the domain conflict, not the
    // constraint violation. Any other integrity failure is a real defect and must surface.
    try {
      return userAccountRepository.saveAndFlush(
          UserAccount.builder()
              .email(command.email())
              .displayName(command.displayName())
              .passwordHash(passwordEncoder.encode(command.password()))
              .accountRole(AccountRole.ADMIN)
              .homeHouseholdId(homeHouseholdId)
              .householdRole(HouseholdRole.OWNER)
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
