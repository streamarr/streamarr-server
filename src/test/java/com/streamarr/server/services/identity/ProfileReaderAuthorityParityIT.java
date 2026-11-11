package com.streamarr.server.services.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileAuthority;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupport.TestIdentity;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What the reader details say an Account is to a Profile must be what Cedar decides from its facts:
 * a ProfileManager may invite another manager, and a manager or a supervising HouseholdAdmin may
 * manage the Profile's PIN. Neither permit has a ServerAdmin arm, so they hold for every reader.
 */
@Tag("IntegrationTest")
@DisplayName("Profile Reader Authority Parity Integration Tests")
class ProfileReaderAuthorityParityIT extends AbstractIntegrationTest {

  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private AuthorizationService authorizationService;
  @Autowired private ProfileReaderDetailsService readerDetails;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private ProfileManagerRepository profileManagerRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private TransactionTemplate transactionTemplate;

  private final List<TestIdentity> identities = new ArrayList<>();
  private TestIdentity smith;
  private TestIdentity cabin;
  private Profile kid;

  /** Each kind of reader of ADR 0024's read table, the Profile it reads and what it is to it. */
  enum Reader {
    MANAGER(ProfileAuthority.MANAGES),
    OWN_ACCOUNT_OF_AN_UNRESTRICTED_ADULT(ProfileAuthority.MANAGES),
    OWN_ACCOUNT_OF_A_SUPERVISED_PROFILE(null),
    HOME_HOUSEHOLD_ADMIN_OF_A_KID(ProfileAuthority.SUPERVISES),
    HOME_HOUSEHOLD_ADMIN_OF_AN_UNRESTRICTED_ADULT(null),
    HOSTING_HOUSEHOLD_ADMIN_OF_A_KID(ProfileAuthority.SUPERVISES),
    MEMBER_WHO_IS_NO_ADMIN(null),
    UNRELATED_ACCOUNT(null),
    SERVER_ADMIN_WHO_MANAGES_NOTHING(null);

    private final ProfileAuthority expected;

    Reader(ProfileAuthority expected) {
      this.expected = expected;
    }
  }

  private record Reading(TestIdentity reader, Profile profile) {}

  @BeforeEach
  void setUp() {
    smith = track(authTestSupport.createIdentity());
    cabin = track(authTestSupport.createIdentity());
    kid = profileManagedBy(smith, ProfileFixture.kidProfileBuilder());
    makeAvailable(kid, cabin);
  }

  @AfterEach
  void tearDown() {
    identities.reversed().forEach(authTestSupport::deleteIdentity);
  }

  @ParameterizedTest
  @EnumSource(Reader.class)
  @DisplayName("Should agree with the Cedar facts when each kind of reader reads a Profile")
  void shouldAgreeWithTheCedarFactsWhenEachKindOfReaderReadsAProfile(Reader kind) {
    var reading = arrange(kind);
    var identity = authTestSupport.identityOf(reading.reader());
    var profileId = reading.profile().getId();

    var authority = readerDetails.details(identity, Set.of(profileId)).get(profileId).authority();

    assertThat(authority).isEqualTo(Optional.ofNullable(kind.expected));
    assertThat(authority.filter(ProfileAuthority.MANAGES::equals).isPresent())
        .as("manages, as inviteProfileManager decides it")
        .isEqualTo(
            authorizationService.isAllowed(identity, new Intent.InviteProfileManager(profileId)));
    assertThat(authority.isPresent())
        .as("manages or supervises, as manageProfilePin decides it")
        .isEqualTo(
            authorizationService.isAllowed(identity, new Intent.ManageProfilePin(profileId)));
  }

  private Reading arrange(Reader kind) {
    return switch (kind) {
      case MANAGER -> new Reading(smith, kid);
      case OWN_ACCOUNT_OF_AN_UNRESTRICTED_ADULT -> new Reading(smith, smith.profile());
      case OWN_ACCOUNT_OF_A_SUPERVISED_PROFILE -> {
        var supervised = supervisedMemberOf(smith);
        yield new Reading(supervised, supervised.profile());
      }
      case HOME_HOUSEHOLD_ADMIN_OF_A_KID -> new Reading(secondAdminOf(smith), kid);
      case HOME_HOUSEHOLD_ADMIN_OF_AN_UNRESTRICTED_ADULT -> {
        var adult = profileManagedBy(smith, ProfileFixture.defaultProfileBuilder());
        yield new Reading(secondAdminOf(smith), adult);
      }
      case HOSTING_HOUSEHOLD_ADMIN_OF_A_KID -> new Reading(cabin, kid);
      case MEMBER_WHO_IS_NO_ADMIN ->
          new Reading(track(authTestSupport.createHouseholdMemberIdentity(smith)), kid);
      case UNRELATED_ACCOUNT -> new Reading(track(authTestSupport.createIdentity()), kid);
      case SERVER_ADMIN_WHO_MANAGES_NOTHING ->
          new Reading(track(authTestSupport.createAdminIdentity()), kid);
    };
  }

  /** A Profile that belongs to the manager's Household, available there, with a direct grant. */
  private Profile profileManagedBy(
      TestIdentity manager, Profile.ProfileBuilder<?, ?> profileBuilder) {
    return transactionTemplate.execute(
        _ -> {
          var profile =
              profileRepository.saveAndFlush(
                  profileBuilder.householdId(manager.household().getId()).build());
          profileManagerRepository.saveAndFlush(
              ProfileManager.builder()
                  .accountId(manager.account().getId())
                  .profileId(profile.getId())
                  .build());
          shareRepository.saveAndFlush(
              ProfileHouseholdShare.builder()
                  .profileId(profile.getId())
                  .householdId(manager.household().getId())
                  .status(ProfileShareStatus.ACTIVE)
                  .structural(true)
                  .build());
          return profile;
        });
  }

  private void makeAvailable(Profile profile, TestIdentity host) {
    transactionTemplate.executeWithoutResult(
        _ ->
            shareRepository.saveAndFlush(
                ProfileHouseholdShare.builder()
                    .profileId(profile.getId())
                    .householdId(host.household().getId())
                    .status(ProfileShareStatus.ACTIVE)
                    .build()));
  }

  private TestIdentity secondAdminOf(TestIdentity householdOf) {
    var admin = track(authTestSupport.createHouseholdMemberIdentity(householdOf));
    transactionTemplate.executeWithoutResult(
        _ -> {
          var account = userAccountRepository.findById(admin.account().getId()).orElseThrow();
          account.setHouseholdRole(HouseholdRole.ADMIN);
          userAccountRepository.saveAndFlush(account);
        });
    return admin;
  }

  /** A member whose Personal Profile has a content limit, anchored by the HouseholdAdmin. */
  private TestIdentity supervisedMemberOf(TestIdentity householdAdmin) {
    var member = track(authTestSupport.createHouseholdMemberIdentity(householdAdmin));
    transactionTemplate.executeWithoutResult(
        _ -> {
          profileManagerRepository.saveAndFlush(
              ProfileManager.builder()
                  .accountId(householdAdmin.account().getId())
                  .profileId(member.profile().getId())
                  .build());
          var profile = profileRepository.findById(member.profile().getId()).orElseThrow();
          profile.setMaximumAllowedRatingAge(12);
          profileRepository.saveAndFlush(profile);
        });
    return member;
  }

  private TestIdentity track(TestIdentity identity) {
    identities.add(identity);
    return identity;
  }
}
