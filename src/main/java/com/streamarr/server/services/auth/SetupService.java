package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.AccountProfile;
import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.HouseholdMembership;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.SetupAlreadyCompletedException;
import com.streamarr.server.repositories.auth.AccountProfileRepository;
import com.streamarr.server.repositories.auth.HouseholdMembershipRepository;
import com.streamarr.server.repositories.auth.HouseholdRepository;
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
   * rows to the first profile; the constant (and its twin in graphql.CurrentUser) disappears at the
   * enforcement flip.
   */
  private static final UUID LEGACY_PLACEHOLDER_PROFILE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");

  private static final String DEFAULT_RATING_REGION = "US";

  private static final String EMAIL_UNIQUE_INDEX = "uq_user_account_email";

  private final UserAccountRepository userAccountRepository;
  private final HouseholdRepository householdRepository;
  private final HouseholdMembershipRepository membershipRepository;
  private final ProfileRepository profileRepository;
  private final AccountProfileRepository accountProfileRepository;
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

    // saveAndFlush before each jOOQ statement: Hibernate defers JPA inserts until flush, but
    // the claim and link run as direct SQL against those rows' foreign keys.
    var admin = createAdminAccount(command);

    if (!serverBootstrapRepository.claim(admin.getId())) {
      throw new SetupAlreadyCompletedException();
    }

    var household =
        householdRepository.save(
            Household.builder()
                .name(command.householdName())
                .defaultRatingRegion(DEFAULT_RATING_REGION)
                .build());

    membershipRepository.grantMembership(
        HouseholdMembership.builder()
            .accountId(admin.getId())
            .householdId(household.getId())
            .householdRole(HouseholdRole.OWNER)
            .build());

    var profile =
        profileRepository.saveAndFlush(
            Profile.builder().householdId(household.getId()).name(command.profileName()).build());

    accountProfileRepository.linkProfile(
        AccountProfile.builder()
            .accountId(admin.getId())
            .householdId(household.getId())
            .profileId(profile.getId())
            .build());

    sessionProgressRepository.reassignProfile(LEGACY_PLACEHOLDER_PROFILE_ID, profile.getId());
    watchHistoryRepository.reassignProfile(LEGACY_PLACEHOLDER_PROFILE_ID, profile.getId());

    return SetupResult.builder().admin(admin).household(household).profile(profile).build();
  }

  private UserAccount createAdminAccount(SetupCommand command) {
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
