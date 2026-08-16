package com.streamarr.server.services.auth;

import static com.streamarr.server.fixtures.AuthenticatedIdentityFixture.accountIdentity;
import static com.streamarr.server.fixtures.AuthenticatedIdentityFixture.accountIdentityBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.config.security.AuthThrottleProperties;
import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.SecurityAuditOperation;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.HouseholdAccessDeniedException;
import com.streamarr.server.exceptions.HouseholdOwnershipTransferRequiredException;
import com.streamarr.server.exceptions.InvalidCredentialsException;
import com.streamarr.server.exceptions.KidProfileManagerRequiredException;
import com.streamarr.server.exceptions.ServerAdministrationDeniedException;
import com.streamarr.server.fakes.FakeAuthSessionRepository;
import com.streamarr.server.fakes.FakeHouseholdRepository;
import com.streamarr.server.fakes.FakeProfileHouseholdShareRepository;
import com.streamarr.server.fakes.FakeProfileManagerRepository;
import com.streamarr.server.fakes.FakeProfileRepository;
import com.streamarr.server.fakes.FakeSecurityAuditEventRepository;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

@Tag("UnitTest")
@DisplayName("Household Administration Service Tests")
class HouseholdAdministrationServiceTest {

  private static final String PASSWORD = "correct horse battery staple";

  private final FakeUserAccountRepository accountRepository = new FakeUserAccountRepository();
  private final FakeHouseholdRepository householdRepository = new FakeHouseholdRepository();
  private final FakeAuthSessionRepository sessionRepository = new FakeAuthSessionRepository();
  private final FakeProfileRepository profileRepository = new FakeProfileRepository();
  private final FakeProfileManagerRepository managerRepository = new FakeProfileManagerRepository();
  private final FakeProfileHouseholdShareRepository shareRepository =
      new FakeProfileHouseholdShareRepository();
  private final FakeSecurityAuditEventRepository auditRepository =
      new FakeSecurityAuditEventRepository();
  private final PasswordEncoder passwordEncoder =
      PasswordEncoderFactories.createDelegatingPasswordEncoder();
  private final Clock clock = Clock.fixed(Instant.parse("2026-08-13T17:00:00Z"), ZoneOffset.UTC);
  private final CredentialGuessThrottle credentialThrottle =
      new CredentialGuessThrottle(
          AuthThrottleProperties.builder().maxAttempts(5).window(Duration.ofMinutes(15)).build(),
          clock);
  private final HouseholdAdministrationService service =
      new HouseholdAdministrationService(
          accountRepository,
          householdRepository,
          sessionRepository,
          new ServerAdminAuthorizer(
              accountRepository, new AccountPasswordVerifier(passwordEncoder, credentialThrottle)),
          new KidProfileManagerPolicy(
              profileRepository, managerRepository, shareRepository, accountRepository),
          passwordEncoder,
          clock,
          new SecurityAuditService(auditRepository));

  @Test
  @DisplayName("Should transfer a non owner account and clear every active profile selection")
  void shouldTransferNonOwnerAccountAndClearEveryActiveProfileSelection() {
    var source = householdRepository.save(Household.builder().name("Source").build());
    var target = householdRepository.save(Household.builder().name("Target").build());
    var admin = saveAccount(AccountRole.ADMIN, source.getId(), HouseholdRole.OWNER);
    var transferred = saveAccount(AccountRole.USER, source.getId(), HouseholdRole.MEMBER);
    var session =
        sessionRepository.save(
            AuthSession.builder()
                .accountId(transferred.getId())
                .deviceName("Living room")
                .activeProfileId(UUID.randomUUID())
                .build());

    transferAccount(
        AccountHouseholdTransferCommand.builder()
            .actingAccountId(admin.getId())
            .targetAccountId(transferred.getId())
            .targetHouseholdId(target.getId())
            .targetRole(HouseholdRole.PARENT)
            .password(PASSWORD)
            .reason("Move account to its current home")
            .build());

    assertThat(transferred.getHomeHouseholdId()).isEqualTo(target.getId());
    assertThat(transferred.getHouseholdRole()).isEqualTo(HouseholdRole.PARENT);
    assertThat(session.getActiveProfileId()).isNull();
    assertThat(auditRepository.findAll())
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.getTargetAccountId()).isEqualTo(transferred.getId());
              assertThat(event.getTargetHouseholdId()).isEqualTo(target.getId());
              assertThat(event.getOperation())
                  .isEqualTo(SecurityAuditOperation.ACCOUNT_TRANSFERRED);
            });
  }

  @Test
  @DisplayName("Should transactionally transfer exact household ownership")
  void shouldTransactionallyTransferExactHouseholdOwnership() {
    var household = householdRepository.save(Household.builder().name("Family").build());
    var currentOwner = saveAccount(AccountRole.USER, household.getId(), HouseholdRole.OWNER);
    var nextOwner = saveAccount(AccountRole.USER, household.getId(), HouseholdRole.PARENT);

    transferOwnership(
        HouseholdOwnershipTransferCommand.builder()
            .authority(accountIdentity(currentOwner))
            .householdId(household.getId())
            .targetAccountId(nextOwner.getId())
            .password(PASSWORD)
            .reason("Planned ownership handoff")
            .build());

    assertThat(currentOwner.getHouseholdRole()).isEqualTo(HouseholdRole.PARENT);
    assertThat(nextOwner.getHouseholdRole()).isEqualTo(HouseholdRole.OWNER);
    assertThat(auditRepository.findAll())
        .singleElement()
        .extracting(event -> event.getOperation())
        .isEqualTo(SecurityAuditOperation.HOUSEHOLD_OWNERSHIP_TRANSFERRED);
  }

  @Test
  @DisplayName("Should reject live household promotion absent from signed authority")
  void shouldRejectLiveHouseholdPromotionAbsentFromSignedAuthority() {
    var signedHouseholdId = UUID.randomUUID();
    var liveHousehold = householdRepository.save(Household.builder().name("Live Home").build());
    var promotedOwner = saveAccount(AccountRole.USER, liveHousehold.getId(), HouseholdRole.OWNER);
    var nextOwner = saveAccount(AccountRole.USER, liveHousehold.getId(), HouseholdRole.PARENT);
    var command =
        HouseholdOwnershipTransferCommand.builder()
            .authority(identity(promotedOwner, signedHouseholdId, HouseholdRole.MEMBER))
            .householdId(liveHousehold.getId())
            .targetAccountId(nextOwner.getId())
            .password(PASSWORD)
            .reason("Live promotion must not widen signed authority")
            .build();

    assertThatThrownBy(() -> transferOwnership(command))
        .isInstanceOf(HouseholdAccessDeniedException.class);

    assertThat(promotedOwner.getHouseholdRole()).isEqualTo(HouseholdRole.OWNER);
    assertThat(nextOwner.getHouseholdRole()).isEqualTo(HouseholdRole.PARENT);
  }

  @Test
  @DisplayName("Should reject moving a household owner before ownership is transferred")
  void shouldRejectMovingHouseholdOwnerBeforeOwnershipIsTransferred() {
    var source = householdRepository.save(Household.builder().name("Source").build());
    var target = householdRepository.save(Household.builder().name("Target").build());
    var admin = saveAccount(AccountRole.ADMIN, target.getId(), HouseholdRole.OWNER);
    var owner = saveAccount(AccountRole.USER, source.getId(), HouseholdRole.OWNER);
    var command =
        AccountHouseholdTransferCommand.builder()
            .actingAccountId(admin.getId())
            .targetAccountId(owner.getId())
            .targetHouseholdId(target.getId())
            .targetRole(HouseholdRole.PARENT)
            .password(PASSWORD)
            .reason("Invalid move")
            .build();

    assertThatThrownBy(() -> transferAccount(command))
        .isInstanceOf(HouseholdOwnershipTransferRequiredException.class)
        .hasMessageContaining("ownership");

    assertThat(owner.getHomeHouseholdId()).isEqualTo(source.getId());
  }

  @Test
  @DisplayName("Should reject assigning owner role through account transfer")
  void shouldRejectAssigningOwnerRoleThroughAccountTransfer() {
    var source = householdRepository.save(Household.builder().name("Source").build());
    var target = householdRepository.save(Household.builder().name("Target").build());
    var admin = saveAccount(AccountRole.ADMIN, source.getId(), HouseholdRole.OWNER);
    var member = saveAccount(AccountRole.USER, source.getId(), HouseholdRole.MEMBER);
    var command =
        AccountHouseholdTransferCommand.builder()
            .actingAccountId(admin.getId())
            .targetAccountId(member.getId())
            .targetHouseholdId(target.getId())
            .targetRole(HouseholdRole.OWNER)
            .password(PASSWORD)
            .reason("Invalid ownership shortcut")
            .build();

    assertThatThrownBy(() -> transferAccount(command))
        .isInstanceOf(HouseholdOwnershipTransferRequiredException.class);
  }

  @Test
  @DisplayName("Should reject account transfer without live ServerAdmin authority")
  void shouldRejectAccountTransferWithoutLiveServerAdminAuthority() {
    var source = householdRepository.save(Household.builder().name("Source").build());
    var target = householdRepository.save(Household.builder().name("Target").build());
    var actor = saveAccount(AccountRole.USER, source.getId(), HouseholdRole.PARENT);
    var transferred = saveAccount(AccountRole.USER, source.getId(), HouseholdRole.MEMBER);
    var command =
        AccountHouseholdTransferCommand.builder()
            .actingAccountId(actor.getId())
            .targetAccountId(transferred.getId())
            .targetHouseholdId(target.getId())
            .targetRole(HouseholdRole.MEMBER)
            .password(PASSWORD)
            .reason("Unauthorized move")
            .build();

    assertThatThrownBy(() -> transferAccount(command))
        .isInstanceOf(ServerAdministrationDeniedException.class);

    assertThat(transferred.getHomeHouseholdId()).isEqualTo(source.getId());
  }

  @Test
  @DisplayName("Should reject account transfer that removes the last local kid parent manager")
  void shouldRejectAccountTransferThatRemovesLastLocalKidParentManager() {
    var source = householdRepository.save(Household.builder().name("Source").build());
    var target = householdRepository.save(Household.builder().name("Target").build());
    var admin = saveAccount(AccountRole.ADMIN, source.getId(), HouseholdRole.OWNER);
    var localParent = saveAccount(AccountRole.USER, source.getId(), HouseholdRole.PARENT);
    var remoteParent = saveAccount(AccountRole.USER, target.getId(), HouseholdRole.PARENT);
    var kid =
        profileRepository.save(
            Profile.builder()
                .name("Portable Kid")
                .kind(ProfileKind.KID)
                .maximumAllowedRatingAge(7)
                .build());
    managerRepository.save(
        ProfileManager.builder().accountId(localParent.getId()).profileId(kid.getId()).build());
    managerRepository.save(
        ProfileManager.builder().accountId(remoteParent.getId()).profileId(kid.getId()).build());
    shareRepository.save(
        ProfileHouseholdShare.builder()
            .profileId(kid.getId())
            .householdId(source.getId())
            .status(ProfileShareStatus.ACTIVE)
            .build());
    var command =
        AccountHouseholdTransferCommand.builder()
            .actingAccountId(admin.getId())
            .targetAccountId(localParent.getId())
            .targetHouseholdId(target.getId())
            .targetRole(HouseholdRole.PARENT)
            .password(PASSWORD)
            .reason("Unsafe move")
            .build();

    assertThatThrownBy(() -> transferAccount(command))
        .isInstanceOf(KidProfileManagerRequiredException.class);

    assertThat(localParent.getHomeHouseholdId()).isEqualTo(source.getId());
  }

  @Test
  @DisplayName("Should let server administrator transfer household ownership")
  void shouldLetServerAdministratorTransferHouseholdOwnership() {
    var household = householdRepository.save(Household.builder().name("Family").build());
    var outsideHousehold = UUID.randomUUID();
    var admin = saveAccount(AccountRole.ADMIN, outsideHousehold, HouseholdRole.MEMBER);
    var currentOwner = saveAccount(AccountRole.USER, household.getId(), HouseholdRole.OWNER);
    var nextOwner = saveAccount(AccountRole.USER, household.getId(), HouseholdRole.PARENT);

    transferOwnership(
        HouseholdOwnershipTransferCommand.builder()
            .authority(accountIdentity(admin))
            .householdId(household.getId())
            .targetAccountId(nextOwner.getId())
            .password(PASSWORD)
            .reason("Administrator recovery")
            .build());

    assertThat(currentOwner.getHouseholdRole()).isEqualTo(HouseholdRole.PARENT);
    assertThat(nextOwner.getHouseholdRole()).isEqualTo(HouseholdRole.OWNER);
  }

  @Test
  @DisplayName("Should reject ownership transfer without owner or administrator authority")
  void shouldRejectOwnershipTransferWithoutOwnerOrAdministratorAuthority() {
    var household = householdRepository.save(Household.builder().name("Family").build());
    saveAccount(AccountRole.USER, household.getId(), HouseholdRole.OWNER);
    var parent = saveAccount(AccountRole.USER, household.getId(), HouseholdRole.PARENT);
    var nextOwner = saveAccount(AccountRole.USER, household.getId(), HouseholdRole.MEMBER);
    var command =
        HouseholdOwnershipTransferCommand.builder()
            .authority(accountIdentity(parent))
            .householdId(household.getId())
            .targetAccountId(nextOwner.getId())
            .password(PASSWORD)
            .reason("Unauthorized handoff")
            .build();

    assertThatThrownBy(() -> transferOwnership(command))
        .isInstanceOf(HouseholdAccessDeniedException.class);
  }

  @Test
  @DisplayName("Should reject ownership transfer by disabled administrator")
  void shouldRejectOwnershipTransferByDisabledAdministrator() {
    var household = householdRepository.save(Household.builder().name("Family").build());
    saveAccount(AccountRole.USER, household.getId(), HouseholdRole.OWNER);
    var disabledAdmin = saveAccount(AccountRole.ADMIN, UUID.randomUUID(), HouseholdRole.MEMBER);
    disabledAdmin.setEnabled(false);
    accountRepository.save(disabledAdmin);
    var nextOwner = saveAccount(AccountRole.USER, household.getId(), HouseholdRole.PARENT);
    var command =
        HouseholdOwnershipTransferCommand.builder()
            .authority(accountIdentity(disabledAdmin))
            .householdId(household.getId())
            .targetAccountId(nextOwner.getId())
            .password(PASSWORD)
            .reason("Disabled administrator")
            .build();

    assertThatThrownBy(() -> transferOwnership(command))
        .isInstanceOf(HouseholdAccessDeniedException.class);
  }

  @Test
  @DisplayName("Should reject ownership transfer with invalid credentials")
  void shouldRejectOwnershipTransferWithInvalidCredentials() {
    var household = householdRepository.save(Household.builder().name("Family").build());
    var owner = saveAccount(AccountRole.USER, household.getId(), HouseholdRole.OWNER);
    var nextOwner = saveAccount(AccountRole.USER, household.getId(), HouseholdRole.PARENT);
    var command =
        HouseholdOwnershipTransferCommand.builder()
            .authority(accountIdentity(owner))
            .householdId(household.getId())
            .targetAccountId(nextOwner.getId())
            .password("wrong password")
            .reason("Invalid credentials")
            .build();

    assertThatThrownBy(() -> transferOwnership(command))
        .isInstanceOf(InvalidCredentialsException.class);
  }

  @Test
  @DisplayName("Should reject transferring household ownership to current owner")
  void shouldRejectTransferringHouseholdOwnershipToCurrentOwner() {
    var household = householdRepository.save(Household.builder().name("Family").build());
    var owner = saveAccount(AccountRole.USER, household.getId(), HouseholdRole.OWNER);
    var command =
        HouseholdOwnershipTransferCommand.builder()
            .authority(accountIdentity(owner))
            .householdId(household.getId())
            .targetAccountId(owner.getId())
            .password(PASSWORD)
            .reason("No-op handoff")
            .build();

    assertThatThrownBy(() -> transferOwnership(command))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("already owns");
  }

  @Test
  @DisplayName("Should require nonblank reason for household administration")
  void shouldRequireNonblankReasonForHouseholdAdministration() {
    var actorId = UUID.randomUUID();
    var targetId = UUID.randomUUID();
    var householdId = UUID.randomUUID();
    var missingReason =
        AccountHouseholdTransferCommand.builder()
            .actingAccountId(actorId)
            .targetAccountId(targetId)
            .targetHouseholdId(householdId)
            .targetRole(HouseholdRole.MEMBER)
            .password(PASSWORD)
            .reason("")
            .build();
    var blankReason =
        HouseholdOwnershipTransferCommand.builder()
            .authority(
                accountIdentityBuilder()
                    .accountId(actorId)
                    .householdId(householdId)
                    .householdRole(HouseholdRole.OWNER)
                    .build())
            .householdId(householdId)
            .targetAccountId(targetId)
            .password(PASSWORD)
            .reason("  ")
            .build();

    assertThatThrownBy(() -> transferAccount(missingReason))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> transferOwnership(blankReason))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private void transferAccount(AccountHouseholdTransferCommand command) {
    service.transferAccount(service.prepare(command));
  }

  private void transferOwnership(HouseholdOwnershipTransferCommand command) {
    service.transferOwnership(service.prepare(command));
  }

  private UserAccount saveAccount(
      AccountRole accountRole, UUID householdId, HouseholdRole householdRole) {
    return accountRepository.save(
        UserAccount.builder()
            .email("account-" + UUID.randomUUID() + "@example.com")
            .displayName("Account")
            .passwordHash(passwordEncoder.encode(PASSWORD))
            .accountRole(accountRole)
            .homeHouseholdId(householdId)
            .householdRole(householdRole)
            .build());
  }

  private AuthenticatedIdentity identity(
      UserAccount account, UUID householdId, HouseholdRole householdRole) {
    return AuthenticatedIdentity.builder()
        .accountId(account.getId())
        .role(account.getAccountRole())
        .authSessionId(UUID.randomUUID())
        .scope(TokenScope.ACCOUNT)
        .householdId(householdId)
        .householdRole(householdRole)
        .build();
  }
}
