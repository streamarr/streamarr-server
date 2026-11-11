package com.streamarr.server.services.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.ShareCode;
import com.streamarr.server.domain.auth.ShareCodeStatus;
import com.streamarr.server.domain.auth.SourceHouseholdAccess;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ShareCodeRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.ShareCodes;
import com.streamarr.server.services.identity.AccountLifecycleService.AdministrativelyDeleteAccountCommand;
import com.streamarr.server.services.identity.AccountLifecycleService.ProfileCleanup;
import com.streamarr.server.services.identity.AccountLifecycleService.TransferAccountCommand;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.PostgresLockTestSupport;
import com.streamarr.server.support.TestFixtureWork;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A live share code stays live only while the Account that issued it may still issue one for the
 * code's Household, by either role (issue #870, decision 10, provisional). Each change that can
 * take that away is driven here through the service people use.
 */
@Tag("IntegrationTest")
@DisplayName("Share Code Issuer Authority Integration Tests")
class ShareCodeIssuerAuthorityIT extends AbstractIntegrationTest {

  @Autowired private ShareCodeService shareCodeService;
  @Autowired private ShareCodeRepository shareCodeRepository;
  @Autowired private ProfileSharingService profileSharingService;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private ShareCodes shareCodes;
  @Autowired private AccountAdministrationService accountAdministration;
  @Autowired private AccountLifecycleService accountLifecycle;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private DataSource dataSource;

  private final List<AuthTestSupport.TestIdentity> identities = new ArrayList<>();
  private AuthTestSupport.TestIdentity actor;
  private AuthTestSupport.TestIdentity host;

  @BeforeEach
  void setUp() {
    actor = track(authTestSupport.createAdminIdentity());
    host = track(authTestSupport.createIdentity());
  }

  @AfterEach
  void tearDown() {
    identities.reversed().forEach(authTestSupport::deleteIdentity);
  }

  @Test
  @DisplayName("Should invalidate a HouseholdAdmin's codes when their role is revoked")
  void shouldInvalidateHouseholdAdminsCodesWhenTheirRoleIsRevoked() {
    var otherAdmin = secondAdminOf(host);
    var hostsCode = issue(host, host.household().getId());
    var othersCode = issue(otherAdmin, host.household().getId());

    accepted(accountAdministration.revokeHouseholdAdmin(fresh(actor), host.account().getId()));

    assertThat(stored(hostsCode))
        .satisfies(
            shareCode -> {
              assertThat(shareCode.getStatus()).isEqualTo(ShareCodeStatus.INVALIDATED);
              assertThat(shareCode.getEndedAt()).isNotNull();
            });
    assertThat(stored(othersCode).getStatus()).isEqualTo(ShareCodeStatus.LIVE);
  }

  @Test
  @DisplayName("Should invalidate the codes only when the issuer has lost both roles")
  void shouldInvalidateCodesOnlyWhenIssuerHasLostBothRoles() {
    var both = track(authTestSupport.createAdminIdentity());
    secondAdminOf(both);
    var code = issue(both, both.household().getId());

    accepted(accountAdministration.revokeHouseholdAdmin(fresh(actor), both.account().getId()));
    assertThat(stored(code).getStatus()).isEqualTo(ShareCodeStatus.LIVE);

    accepted(
        accountAdministration.revokeServerAdmin(fresh(actor), both.account().getId(), "rotation"));
    assertThat(stored(code).getStatus()).isEqualTo(ShareCodeStatus.INVALIDATED);
  }

  @Test
  @DisplayName(
      "Should invalidate only the codes for other Households when a ServerAdmin role is revoked")
  void shouldInvalidateOnlyCodesForOtherHouseholdsWhenServerAdminRoleIsRevoked() {
    var both = track(authTestSupport.createAdminIdentity());
    var forHost = issue(both, host.household().getId());
    var forOwn = issue(both, both.household().getId());

    accepted(
        accountAdministration.revokeServerAdmin(fresh(actor), both.account().getId(), "rotation"));

    assertThat(stored(forHost).getStatus()).isEqualTo(ShareCodeStatus.INVALIDATED);
    assertThat(stored(forOwn).getStatus()).isEqualTo(ShareCodeStatus.LIVE);
  }

  @Test
  @DisplayName("Should invalidate every code an Account issued when the Account is disabled")
  void shouldInvalidateEveryCodeAccountIssuedWhenAccountIsDisabled() {
    var both = track(authTestSupport.createAdminIdentity());
    var forHost = issue(both, host.household().getId());
    var forOwn = issue(both, both.household().getId());

    accepted(accountAdministration.disableAccount(fresh(actor), both.account().getId()));

    assertThat(stored(forHost).getStatus()).isEqualTo(ShareCodeStatus.INVALIDATED);
    assertThat(stored(forOwn).getStatus()).isEqualTo(ShareCodeStatus.INVALIDATED);
  }

  @Test
  @DisplayName("Should invalidate a HouseholdAdmin's codes when they move to another Household")
  void shouldInvalidateHouseholdAdminsCodesWhenTheyMoveToAnotherHousehold() {
    var mover = secondAdminOf(host);
    var destination = track(authTestSupport.createIdentity());
    var code = issue(mover, host.household().getId());

    accepted(
        accountLifecycle.transferAccount(
            fresh(actor),
            TransferAccountCommand.builder()
                .accountId(mover.account().getId())
                .destinationHouseholdId(destination.household().getId())
                .sourceHouseholdAccess(SourceHouseholdAccess.KEEP_AS_VISITOR)
                .reason("relocated")
                .build()));

    assertThat(stored(code).getStatus()).isEqualTo(ShareCodeStatus.INVALIDATED);
  }

  @Test
  @DisplayName("Should invalidate an Account's codes when the Account is deleted")
  void shouldInvalidateAccountsCodesWhenAccountIsDeleted() {
    var departing = secondAdminOf(host);
    var code = issue(departing, host.household().getId());

    accepted(
        accountLifecycle.administrativelyDeleteAccount(
            fresh(actor),
            AdministrativelyDeleteAccountCommand.builder()
                .accountId(departing.account().getId())
                .profileCleanup(ProfileCleanup.ERASE_PROFILE)
                .reason("departed")
                .build()));

    assertThat(stored(code))
        .satisfies(
            shareCode -> {
              assertThat(shareCode.getStatus()).isEqualTo(ShareCodeStatus.INVALIDATED);
              assertThat(shareCode.getIssuerAccountId()).isNull();
            });
  }

  @Test
  @DisplayName("Should leave a code that already ended as it was when its issuer loses the role")
  void shouldLeaveCodeThatAlreadyEndedAsItWasWhenItsIssuerLosesRole() {
    secondAdminOf(host);
    var code = issue(host, host.household().getId());
    accepted(shareCodeService.withdrawShareCode(authTestSupport.identityOf(host), code));

    accepted(accountAdministration.revokeHouseholdAdmin(fresh(actor), host.account().getId()));

    assertThat(stored(code).getStatus()).isEqualTo(ShareCodeStatus.WITHDRAWN);
  }

  @Test
  @DisplayName("Should refuse to issue when the issuer's demotion commits while the issue waits")
  void shouldRefuseToIssueWhenIssuersDemotionCommitsWhileIssueWaits() throws Exception {
    secondAdminOf(host);
    try (var demotion = dataSource.getConnection();
        var observer = dataSource.getConnection();
        var executor = TestFixtureWork.newExecutorRunningAsTestFixtureActor()) {
      demotion.setAutoCommit(false);
      demote(demotion, host.account().getId());

      var issue =
          executor.submit(
              () ->
                  shareCodeService.issueShareCode(
                      authTestSupport.identityOf(host), host.household().getId()));
      PostgresLockTestSupport.awaitBlockedBackendPidRunning(
          observer, PostgresLockTestSupport.backendPid(demotion), "%user_account%");
      demotion.commit();

      assertThat(issue.get(10, TimeUnit.SECONDS))
          .isEqualTo(Outcome.rejected(new ShareCodeRejections.HouseholdNotFound()));
    }

    assertThat(
            shareCodeRepository.findByIssuerAccountIdAndStatus(
                host.account().getId(), ShareCodeStatus.LIVE))
        .isEmpty();
  }

  @Test
  @DisplayName("Should refuse an offer when the code's issuer loses the role after a lookup")
  void shouldRefuseOfferWhenCodesIssuerLosesRoleAfterLookup() {
    secondAdminOf(host);
    var offerer = track(authTestSupport.createIdentity());
    var code = authTestSupport.shareCodeFor(host);
    assertThat(
            accepted(
                shareCodeService.lookUpShareCode(
                    authTestSupport.identityOf(offerer), presenting(code))))
        .isEqualTo(host.household().getName());

    accepted(accountAdministration.revokeHouseholdAdmin(fresh(actor), host.account().getId()));

    assertThat(offerOwnProfile(offerer, code))
        .isEqualTo(Outcome.rejected(new ShareCodeRejections.ShareCodeRefused()));
    assertThat(storedByCode(code).getStatus()).isEqualTo(ShareCodeStatus.INVALIDATED);
  }

  @Test
  @DisplayName("Should refuse an offer when the code's issuer loses the role while the offer waits")
  void shouldRefuseOfferWhenCodesIssuerLosesRoleWhileOfferWaits() throws Exception {
    secondAdminOf(host);
    var offerer = track(authTestSupport.createIdentity());
    var code = authTestSupport.shareCodeFor(host);
    var offeredProfile =
        PostgresLockTestSupport.RowLockTarget.builder()
            .dataSource(dataSource)
            .table("profile")
            .rowId(offerer.profile().getId())
            .build();

    Outcome<ProfileHouseholdShare, ShareRejections.Offer> outcome;
    try (var executor = TestFixtureWork.newExecutorRunningAsTestFixtureActor();
        var observer = dataSource.getConnection();
        var profileLock = PostgresLockTestSupport.lockRow(offeredProfile)) {
      // The offer has presented the code while it was live and now waits to write.
      var offer = executor.submit(() -> offerOwnProfile(offerer, code));
      PostgresLockTestSupport.awaitBlockedBackendPidRunning(
          observer, profileLock.backendPid(), "%profile%for update%");

      accepted(accountAdministration.revokeHouseholdAdmin(fresh(actor), host.account().getId()));
      profileLock.release();
      outcome = offer.get(10, TimeUnit.SECONDS);
    }

    assertThat(outcome).isEqualTo(Outcome.rejected(new ShareCodeRejections.ShareCodeRefused()));
    assertThat(storedByCode(code).getStatus()).isEqualTo(ShareCodeStatus.INVALIDATED);
    assertThat(shareRepository.findAll())
        .filteredOn(share -> share.getProfileId().equals(offerer.profile().getId()))
        .filteredOn(share -> share.getHouseholdId().equals(host.household().getId()))
        .isEmpty();
  }

  @Test
  @DisplayName("Should keep the offer and its spent code when the issuer loses the role after it")
  void shouldKeepOfferAndItsSpentCodeWhenIssuerLosesRoleAfterIt() {
    secondAdminOf(host);
    var offerer = track(authTestSupport.createIdentity());
    var code = authTestSupport.shareCodeFor(host);
    var offered = accepted(offerOwnProfile(offerer, code));

    accepted(accountAdministration.revokeHouseholdAdmin(fresh(actor), host.account().getId()));

    assertThat(storedByCode(code).getStatus()).isEqualTo(ShareCodeStatus.SPENT);
    assertThat(shareRepository.findById(offered.getId()).orElseThrow().getStatus())
        .isEqualTo(ProfileShareStatus.PENDING);
  }

  private Outcome<ProfileHouseholdShare, ShareRejections.Offer> offerOwnProfile(
      AuthTestSupport.TestIdentity offerer, String code) {
    return profileSharingService.offerProfileShare(
        authTestSupport.identityOf(offerer), offerer.profile().getId(), presenting(code));
  }

  private static ShareCodeCommand presenting(String code) {
    return ShareCodeCommand.builder().code(code).ipAddress("203.0.113.7").build();
  }

  private ShareCode storedByCode(String code) {
    return shareCodeRepository
        .findByCodeDigest(shareCodes.digestOf(code).orElseThrow())
        .orElseThrow();
  }

  /** Demotes the Account on the given connection and holds its row until that transaction ends. */
  private void demote(Connection connection, UUID accountId) throws SQLException {
    try (var actor =
            connection.prepareStatement("SELECT set_config('streamarr.actor_id', ?, true)");
        var update =
            connection.prepareStatement(
                "UPDATE user_account SET household_role = 'MEMBER' WHERE id = ?")) {
      actor.setString(1, this.actor.account().getId().toString());
      actor.execute();
      update.setObject(1, accountId);
      assertThat(update.executeUpdate()).isOne();
    }
  }

  /** A member of the identity's Household, promoted to HouseholdAdmin beside it. */
  private AuthTestSupport.TestIdentity secondAdminOf(AuthTestSupport.TestIdentity householdOf) {
    var member = authTestSupport.createHouseholdMemberIdentity(householdOf);
    accepted(accountAdministration.grantHouseholdAdmin(fresh(actor), member.account().getId()));
    return member;
  }

  private UUID issue(AuthTestSupport.TestIdentity issuer, UUID householdId) {
    return accepted(
            shareCodeService.issueShareCode(authTestSupport.identityOf(issuer), householdId))
        .shareCode()
        .shareCode()
        .getId();
  }

  private ShareCode stored(UUID shareCodeId) {
    return shareCodeRepository.findById(shareCodeId).orElseThrow();
  }

  private AuthenticatedIdentity fresh(AuthTestSupport.TestIdentity identity) {
    return authTestSupport.freshIdentityOf(identity);
  }

  private AuthTestSupport.TestIdentity track(AuthTestSupport.TestIdentity identity) {
    identities.add(identity);
    return identity;
  }

  private static <T, R> T accepted(Outcome<T, R> outcome) {
    return outcome.fold(
        result -> result,
        rejections -> {
          throw new AssertionError("rejected: " + rejections);
        });
  }
}
