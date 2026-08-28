package com.streamarr.server.services.auth;

import static com.streamarr.server.support.OutcomeTestSupport.accepted;
import static com.streamarr.server.support.OutcomeTestSupport.rejectionOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.AccountInvitation;
import com.streamarr.server.domain.auth.AccountInvitationMode;
import com.streamarr.server.domain.auth.AccountInvitationReoffer;
import com.streamarr.server.domain.auth.AccountInvitationStatus;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.exceptions.InvalidOneTimeCodeException;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.repositories.auth.AccountInvitationReofferRepository;
import com.streamarr.server.repositories.auth.AccountInvitationRepository;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AccountInvitationService.AcceptInvitationCommand;
import com.streamarr.server.services.auth.AccountInvitationService.AcceptedInvitation;
import com.streamarr.server.services.identity.AdministrationQueryService;
import com.streamarr.server.services.identity.CredentialIssuanceService;
import com.streamarr.server.services.identity.CredentialIssuanceService.IssueInvitationForProfileCommand;
import com.streamarr.server.services.identity.CredentialRejections;
import com.streamarr.server.services.identity.ProfileSharingService;
import com.streamarr.server.services.identity.SessionContextService;
import com.streamarr.server.services.pagination.KeysetPaginationOptions;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import com.streamarr.server.services.pagination.OrderMediaBy;
import com.streamarr.server.services.pagination.PageItem;
import com.streamarr.server.services.pagination.PaginationDirection;
import com.streamarr.server.services.pagination.PaginationOptions;
import com.streamarr.server.support.AuthTestSupport;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.jooq.SortOrder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Account Invitation LINK Concurrency Integration Tests")
class AccountInvitationLinkConcurrencyIT extends AbstractIntegrationTest {

  private static final String FIRST_RIVAL_EMAIL = "link-rival-one@example.com";
  private static final String SECOND_RIVAL_EMAIL = "link-rival-two@example.com";
  private static final String SHARE_RACE_EMAIL = "link-share-race@example.com";
  private static final String DUPLICATE_REOFFER_EMAIL = "link-duplicate-reoffer@example.com";
  private static final String RESTRICTED_REOFFER_EMAIL = "link-restricted-reoffer@example.com";
  private static final String CROSS_HOME_FIRST_EMAIL = "link-cross-home-one@example.com";
  private static final String CROSS_HOME_SECOND_EMAIL = "link-cross-home-two@example.com";
  private static final String HISTORY_WINNER_EMAIL = "link-history-winner@example.com";
  private static final String HISTORY_EXPIRED_EMAIL = "link-history-expired@example.com";
  private static final String EXPIRED_HOME_EMAIL = "link-expired-home@example.com";
  private static final String EXPIRED_REOFFER_EMAIL = "link-expired-reoffer@example.com";
  private static final String STALE_REOFFER_EMAIL = "link-stale-reoffer@example.com";

  @Autowired private AccountInvitationService invitationService;
  @Autowired private CredentialIssuanceService credentialIssuanceService;
  @Autowired private AdministrationQueryService administrationQueryService;
  @Autowired private ProfileSharingService profileSharingService;
  @Autowired private SessionContextService sessionContextService;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private AccountInvitationRepository invitationRepository;
  @Autowired private AccountInvitationReofferRepository reofferRepository;
  @Autowired private AuthSessionRepository sessionRepository;
  @Autowired private UserAccountRepository accountRepository;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private ProfileManagerRepository managerRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private OpaqueOneTimeCodes opaqueCodes;
  @Autowired private TransactionTemplate transactions;
  @Autowired private DataSource dataSource;

  private AuthTestSupport.TestIdentity sourceAdmin;
  private AuthTestSupport.TestIdentity targetAdmin;

  @BeforeEach
  void setUp() {
    sourceAdmin = authTestSupport.createAdminIdentity();
  }

  @AfterEach
  void tearDown() {
    invitationRepository.deleteAll();
    invitationRepository.flush();
    deleteLinkedAccount(FIRST_RIVAL_EMAIL);
    deleteLinkedAccount(SECOND_RIVAL_EMAIL);
    deleteLinkedAccount(SHARE_RACE_EMAIL);
    deleteLinkedAccount(DUPLICATE_REOFFER_EMAIL);
    deleteLinkedAccount(RESTRICTED_REOFFER_EMAIL);
    deleteLinkedAccount(CROSS_HOME_FIRST_EMAIL);
    deleteLinkedAccount(CROSS_HOME_SECOND_EMAIL);
    deleteLinkedAccount(HISTORY_WINNER_EMAIL);
    deleteLinkedAccount(EXPIRED_HOME_EMAIL);
    deleteLinkedAccount(EXPIRED_REOFFER_EMAIL);
    deleteLinkedAccount(STALE_REOFFER_EMAIL);
    authTestSupport.deleteIdentity(sourceAdmin);
    if (targetAdmin != null) {
      authTestSupport.deleteIdentity(targetAdmin);
    }
  }

  @Test
  @DisplayName(
      "Should reject one rival LINK acceptance as an invalid code when both are accepted concurrently")
  void shouldRejectOneRivalLinkAcceptanceAsInvalidCodeWhenBothAcceptedConcurrently()
      throws Exception {
    var orphan = orphanAtHome();
    var firstCode = pendingLinkInvitation(orphan, FIRST_RIVAL_EMAIL);
    var secondCode = pendingLinkInvitation(orphan, SECOND_RIVAL_EMAIL);
    var successes = new ArrayList<AcceptedInvitation>();
    var failures = new ArrayList<Throwable>();

    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement();
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      connection.setAutoCommit(false);
      var blockerPid = backendPid(connection);
      statement.execute("LOCK TABLE user_account IN SHARE MODE");
      var first = executor.submit(() -> invitationService.accept(acceptCommand(firstCode)));
      var second = executor.submit(() -> invitationService.accept(acceptCommand(secondCode)));

      try {
        await().atMost(Duration.ofSeconds(5)).until(() -> blockedConnectionCount(blockerPid) >= 2);
      } finally {
        connection.rollback();
      }

      collect(first, successes, failures);
      collect(second, successes, failures);
    }

    assertThat(successes).hasSize(1);
    assertThat(failures).singleElement().isInstanceOf(InvalidOneTimeCodeException.class);
  }

  @Test
  @DisplayName("Should reject LINK issuance when the Profile is linked while issuance waits")
  void shouldRejectLinkIssuanceWhenProfileIsLinkedWhileIssuanceWaits() throws Exception {
    var orphan = orphanAtHome();
    var acceptedCode = pendingLinkInvitation(orphan, FIRST_RIVAL_EMAIL);

    try (var issuanceLock = holdInvitationIssuanceLock(SECOND_RIVAL_EMAIL);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var issuance =
          executor.submit(
              () ->
                  credentialIssuanceService.issueAccountInvitationForProfile(
                      accountIdentity(sourceAdmin),
                      linkInvitationCommand(orphan, SECOND_RIVAL_EMAIL)));
      var blockerPid = backendPid(issuanceLock);
      await().atMost(Duration.ofSeconds(5)).until(() -> blockedConnectionCount(blockerPid) == 1);

      try {
        invitationService.accept(acceptCommand(acceptedCode));
      } finally {
        issuanceLock.rollback();
      }

      assertThat(rejectionOf(issuance.get(15, TimeUnit.SECONDS)))
          .isInstanceOf(CredentialRejections.ProfileAlreadyLinked.class);
    }
  }

  @Test
  @DisplayName("Should reject LINK issuance when a reoffer visit ends while issuance waits")
  void shouldRejectLinkIssuanceWhenReofferVisitEndsWhileIssuanceWaits() throws Exception {
    targetAdmin = authTestSupport.createIdentity();
    var orphan = orphanAtHome();
    var visit = activeShare(orphan, targetAdmin.household().getId());
    var command =
        linkInvitationCommand(orphan, STALE_REOFFER_EMAIL).toBuilder()
            .reofferHouseholdIds(List.of(targetAdmin.household().getId()))
            .build();

    try (var issuanceLock = holdInvitationIssuanceLock(STALE_REOFFER_EMAIL);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var issuance =
          executor.submit(
              () ->
                  credentialIssuanceService.issueAccountInvitationForProfile(
                      accountIdentity(sourceAdmin), command));
      var blockerPid = backendPid(issuanceLock);
      await().atMost(Duration.ofSeconds(5)).until(() -> blockedConnectionCount(blockerPid) == 1);

      try {
        assertThat(
                accepted(
                    profileSharingService.endProfileShare(
                        accountIdentity(targetAdmin), visit.getId())))
            .extracting(ProfileHouseholdShare::getStatus)
            .isEqualTo(ProfileShareStatus.ENDED);
      } finally {
        issuanceLock.rollback();
      }

      assertThat(rejectionOf(issuance.get(15, TimeUnit.SECONDS)))
          .isInstanceOf(CredentialRejections.ReofferHouseholdNotShared.class);
    }
  }

  @Test
  @DisplayName("Should end a share when it activates concurrently with LINK acceptance")
  void shouldEndShareWhenItActivatesConcurrentlyWithLinkAcceptance() throws Exception {
    targetAdmin = authTestSupport.createIdentity();
    var orphan = orphanAtHome();
    var pending = pendingShare(orphan, targetAdmin.household().getId());
    var code = pendingLinkInvitation(orphan, SHARE_RACE_EMAIL);
    var targetIdentity = accountIdentity(targetAdmin);

    try (var connection = dataSource.getConnection();
        var statement =
            connection.prepareStatement(
                "SELECT household_id FROM household_guard WHERE household_id = ? FOR UPDATE");
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      connection.setAutoCommit(false);
      var blockerPid = backendPid(connection);
      statement.setObject(1, targetAdmin.household().getId());
      statement.executeQuery().close();

      var shareAcceptance =
          executor.submit(
              () -> profileSharingService.acceptProfileShare(targetIdentity, pending.getId()));
      Future<AcceptedInvitation> linkAcceptance;
      try {
        await().atMost(Duration.ofSeconds(5)).until(() -> blockedConnectionCount(blockerPid) >= 1);
        linkAcceptance = executor.submit(() -> invitationService.accept(acceptCommand(code)));
        await().atMost(Duration.ofSeconds(5)).until(() -> blockedConnectionCount(blockerPid) >= 2);
      } finally {
        connection.rollback();
      }

      var acceptedShare =
          shareAcceptance
              .get(15, TimeUnit.SECONDS)
              .fold(
                  share -> share,
                  rejections -> {
                    throw new AssertionError("expected acceptance but got " + rejections);
                  });
      assertThat(acceptedShare.getStatus()).isEqualTo(ProfileShareStatus.ACTIVE);
      assertThat(linkAcceptance.get(15, TimeUnit.SECONDS)).isNotNull();
    }

    assertThat(shareRepository.findById(pending.getId()).orElseThrow().getStatus())
        .isEqualTo(ProfileShareStatus.ENDED);
  }

  @Test
  @DisplayName("Should invalidate a share offered before the Profile is linked")
  void shouldInvalidateShareOfferedBeforeProfileIsLinked() throws Exception {
    targetAdmin = authTestSupport.createIdentity();
    var orphan = orphanAtHome();
    var targetHouseholdId = targetAdmin.household().getId();
    var code = pendingLinkInvitation(orphan, SHARE_RACE_EMAIL);
    installShareInsertBarrier(orphan.getId(), targetHouseholdId);

    try (var insertBarrier = holdShareInsertBarrier();
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var offer =
          executor.submit(
              () ->
                  profileSharingService.offerProfileShare(
                      accountIdentity(sourceAdmin), orphan.getId(), targetHouseholdId));
      var blockerPid = backendPid(insertBarrier);
      await().atMost(Duration.ofSeconds(5)).until(() -> blockedConnectionCount(blockerPid) == 1);

      var acceptance = executor.submit(() -> invitationService.accept(acceptCommand(code)));
      try {
        await()
            .atMost(Duration.ofSeconds(5))
            .until(() -> acceptance.isDone() || blockedConnectionCount(blockerPid) == 2);
      } finally {
        insertBarrier.rollback();
      }

      accepted(offer.get(15, TimeUnit.SECONDS));
      assertThat(acceptance.get(15, TimeUnit.SECONDS)).isNotNull();
    } finally {
      removeShareInsertBarrier();
    }

    assertThat(
            profileSharingService
                .pendingShareOffers(
                    accountIdentity(targetAdmin), targetHouseholdId, paginationOptions())
                .items())
        .isEmpty();
  }

  @Test
  @DisplayName("Should reoffer a Household once when its ID is requested twice")
  void shouldReofferHouseholdOnceWhenItsIdIsRequestedTwice() {
    targetAdmin = authTestSupport.createIdentity();
    var orphan = orphanAtHome();
    activeShare(orphan, targetAdmin.household().getId());
    var targetHouseholdId = targetAdmin.household().getId();
    var outcome =
        credentialIssuanceService.issueAccountInvitationForProfile(
            accountIdentity(sourceAdmin),
            IssueInvitationForProfileCommand.builder()
                .recipientEmail(DUPLICATE_REOFFER_EMAIL)
                .householdRole(HouseholdRole.MEMBER)
                .profileId(orphan.getId())
                .reofferHouseholdIds(List.of(targetHouseholdId, targetHouseholdId))
                .build());
    var code =
        outcome.fold(
            CredentialIssuanceService.IssuedInvitation::code,
            rejections -> {
              throw new AssertionError("expected issuance but got " + rejections);
            });

    invitationService.accept(acceptCommand(code));

    assertThat(shareRepository.findByProfileId(orphan.getId()))
        .filteredOn(share -> share.getHouseholdId().equals(targetHouseholdId))
        .extracting(ProfileHouseholdShare::getStatus)
        .containsExactlyInAnyOrder(ProfileShareStatus.ENDED, ProfileShareStatus.PENDING);
  }

  @Test
  @DisplayName("Should let a Household accept a restricted Profile reoffered after LINK")
  void shouldLetHouseholdAcceptRestrictedProfileReofferedAfterLink() {
    targetAdmin = authTestSupport.createIdentity();
    var orphan = orphanAtHome();
    orphan.setKind(ProfileKind.KID);
    profileRepository.saveAndFlush(orphan);
    var targetHouseholdId = targetAdmin.household().getId();
    activeShare(orphan, targetHouseholdId);
    var issued =
        accepted(
            credentialIssuanceService.issueAccountInvitationForProfile(
                accountIdentity(sourceAdmin),
                IssueInvitationForProfileCommand.builder()
                    .recipientEmail(RESTRICTED_REOFFER_EMAIL)
                    .householdRole(HouseholdRole.MEMBER)
                    .profileId(orphan.getId())
                    .reofferHouseholdIds(List.of(targetHouseholdId))
                    .build()));

    invitationService.accept(acceptCommand(issued.code()));
    var targetIdentity = accountIdentity(targetAdmin);
    var reoffer =
        profileSharingService
            .pendingShareOffers(targetIdentity, targetHouseholdId, paginationOptions())
            .items()
            .getFirst()
            .item();

    assertThat(accepted(profileSharingService.acceptProfileShare(targetIdentity, reoffer.getId())))
        .extracting(ProfileHouseholdShare::getStatus)
        .isEqualTo(ProfileShareStatus.ACTIVE);
  }

  @Test
  @DisplayName("Should accept reciprocal cross-Household LINK invitations without deadlock")
  void shouldAcceptReciprocalCrossHouseholdLinkInvitationsWithoutDeadlock() throws Exception {
    targetAdmin = authTestSupport.createAdminIdentity();
    var sourceOrphan = orphanAtHome(sourceAdmin, "Source Visitor");
    var targetOrphan = orphanAtHome(targetAdmin, "Target Visitor");
    activeShare(sourceOrphan, targetAdmin.household().getId());
    activeShare(targetOrphan, sourceAdmin.household().getId());
    var sourceCode = pendingLinkInvitation(sourceOrphan, CROSS_HOME_FIRST_EMAIL, sourceAdmin);
    var targetCode = pendingLinkInvitation(targetOrphan, CROSS_HOME_SECOND_EMAIL, targetAdmin);
    var successes = new ArrayList<AcceptedInvitation>();
    var failures = new ArrayList<Throwable>();
    installAccountInsertBarrier();

    try (var insertBarrier = holdAccountInsertBarrier();
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var sourceAcceptance =
          executor.submit(() -> invitationService.accept(acceptCommand(sourceCode)));
      var targetAcceptance =
          executor.submit(() -> invitationService.accept(acceptCommand(targetCode)));
      var blockerPid = backendPid(insertBarrier);
      try {
        await().atMost(Duration.ofSeconds(5)).until(() -> blockedConnectionCount(blockerPid) == 2);
      } finally {
        insertBarrier.rollback();
      }

      collect(sourceAcceptance, successes, failures);
      collect(targetAcceptance, successes, failures);
    } finally {
      removeAccountInsertBarrier();
    }

    assertThat(successes).hasSize(2);
    assertThat(failures).isEmpty();
  }

  @Test
  @DisplayName(
      "Should clear the selected Profile when LINK acceptance follows concurrent Profile selection")
  void shouldClearSelectedProfileWhenLinkAcceptanceFollowsConcurrentProfileSelection()
      throws Exception {
    targetAdmin = authTestSupport.createIdentity();
    var orphan = orphanAtHome();
    activeShare(orphan, targetAdmin.household().getId());
    var code = pendingLinkInvitation(orphan, SHARE_RACE_EMAIL);

    try (var sessionLock = holdAuthSessionLock(targetAdmin.session().getId());
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var selection =
          executor.submit(
              () ->
                  sessionContextService.recordProfileSelection(
                      accountIdentity(targetAdmin), orphan.getId()));
      var blockerPid = backendPid(sessionLock);
      await().atMost(Duration.ofSeconds(5)).until(() -> blockedConnectionCount(blockerPid) == 1);

      var acceptance = executor.submit(() -> invitationService.accept(acceptCommand(code)));
      try {
        await().atMost(Duration.ofSeconds(5)).until(() -> blockedConnectionCount(blockerPid) == 2);
      } finally {
        sessionLock.rollback();
      }

      assertThat(selection.get(15, TimeUnit.SECONDS).profileId()).contains(orphan.getId());
      assertThat(acceptance.get(15, TimeUnit.SECONDS)).isNotNull();
      assertThat(
              sessionRepository
                  .findById(targetAdmin.session().getId())
                  .orElseThrow()
                  .getSelectedProfileId())
          .isNull();
    }
  }

  @Test
  @DisplayName("Should preserve expired invitation and share history when LINK wins")
  void shouldPreserveExpiredInvitationAndShareHistoryWhenLinkWins() {
    targetAdmin = authTestSupport.createIdentity();
    var orphan = orphanAtHome();
    var now = Instant.now();
    pendingLinkInvitation(
        pendingLinkInvitationBuilder(orphan, HISTORY_EXPIRED_EMAIL, sourceAdmin)
            .expiresAt(now.minus(Duration.ofHours(1))));
    var expiredInvitation =
        invitationRepository.findAll().stream()
            .filter(invitation -> invitation.getRecipientEmail().equals(HISTORY_EXPIRED_EMAIL))
            .findFirst()
            .orElseThrow();
    var expiredOffer =
        shareRepository.saveAndFlush(
            ProfileHouseholdShare.builder()
                .profileId(orphan.getId())
                .householdId(targetAdmin.household().getId())
                .status(ProfileShareStatus.PENDING)
                .offeredByAccountId(sourceAdmin.account().getId())
                .expiresAt(now.minus(Duration.ofHours(1)))
                .build());
    var winnerCode = pendingLinkInvitation(orphan, HISTORY_WINNER_EMAIL);

    invitationService.accept(acceptCommand(winnerCode));

    var invitationHistory =
        administrationQueryService
            .accountInvitations(accountIdentity(sourceAdmin), invitationPaginationOptions())
            .items()
            .stream()
            .map(PageItem::item)
            .filter(invitation -> invitation.getId().equals(expiredInvitation.getId()))
            .findFirst()
            .orElseThrow();
    assertThat(invitationHistory.statusAt(Instant.now()))
        .isEqualTo(AccountInvitationStatus.EXPIRED);
    var shareHistory =
        profileSharingService
            .profileShares(accountIdentity(sourceAdmin), orphan.getId(), paginationOptions())
            .items()
            .stream()
            .map(PageItem::item)
            .filter(share -> share.getId().equals(expiredOffer.getId()))
            .findFirst()
            .orElseThrow();
    assertThat(shareHistory.statusAt(Instant.now())).isEqualTo(ProfileShareStatus.EXPIRED);
  }

  @Test
  @DisplayName("Should preserve an expired home offer when LINK creates the membership share")
  void shouldPreserveExpiredHomeOfferWhenLinkCreatesMembershipShare() {
    var orphan = orphanWithoutHomeShare();
    var expiredOffer =
        shareRepository.saveAndFlush(
            ProfileHouseholdShare.builder()
                .profileId(orphan.getId())
                .householdId(sourceAdmin.household().getId())
                .status(ProfileShareStatus.PENDING)
                .offeredByAccountId(sourceAdmin.account().getId())
                .expiresAt(Instant.now().minus(Duration.ofHours(1)))
                .build());
    var code = pendingLinkInvitation(orphan, EXPIRED_HOME_EMAIL);

    invitationService.accept(acceptCommand(code));

    assertThat(shareRepository.findById(expiredOffer.getId()).orElseThrow().getStatus())
        .isEqualTo(ProfileShareStatus.EXPIRED);
    assertThat(
            shareRepository
                .findByProfileIdAndHouseholdIdAndStatus(
                    orphan.getId(), sourceAdmin.household().getId(), ProfileShareStatus.ACTIVE)
                .orElseThrow())
        .matches(ProfileHouseholdShare::isStructural);
  }

  @Test
  @DisplayName("Should preserve an expired offer when LINK reoffers the Profile")
  void shouldPreserveExpiredOfferWhenLinkReoffersProfile() {
    targetAdmin = authTestSupport.createIdentity();
    var orphan = orphanAtHome();
    var targetHouseholdId = targetAdmin.household().getId();
    var expiredOffer =
        shareRepository.saveAndFlush(
            ProfileHouseholdShare.builder()
                .profileId(orphan.getId())
                .householdId(targetHouseholdId)
                .status(ProfileShareStatus.PENDING)
                .offeredByAccountId(sourceAdmin.account().getId())
                .expiresAt(Instant.now().minus(Duration.ofHours(1)))
                .build());
    var code = pendingLinkInvitation(orphan, EXPIRED_REOFFER_EMAIL);
    var invitation =
        invitationRepository.findAll().stream()
            .filter(candidate -> candidate.getRecipientEmail().equals(EXPIRED_REOFFER_EMAIL))
            .findFirst()
            .orElseThrow();
    reofferRepository.saveAndFlush(
        AccountInvitationReoffer.builder()
            .invitationId(invitation.getId())
            .householdId(targetHouseholdId)
            .householdName(targetAdmin.household().getName())
            .build());

    invitationService.accept(acceptCommand(code));

    assertThat(shareRepository.findById(expiredOffer.getId()).orElseThrow().getStatus())
        .isEqualTo(ProfileShareStatus.EXPIRED);
    assertThat(shareRepository.findByProfileId(orphan.getId()))
        .filteredOn(share -> share.getHouseholdId().equals(targetHouseholdId))
        .extracting(ProfileHouseholdShare::getStatus)
        .containsExactlyInAnyOrder(ProfileShareStatus.EXPIRED, ProfileShareStatus.PENDING);
  }

  private Profile orphanAtHome() {
    return orphanAtHome(sourceAdmin, "Grandpa Joe");
  }

  private Profile orphanWithoutHomeShare() {
    return transactions.execute(
        _ -> {
          var orphan =
              profileRepository.saveAndFlush(
                  ProfileFixture.defaultProfileBuilder()
                      .householdId(sourceAdmin.household().getId())
                      .name("Expired Offer")
                      .build());
          managerRepository.saveAndFlush(
              ProfileManager.builder()
                  .accountId(sourceAdmin.account().getId())
                  .profileId(orphan.getId())
                  .build());
          return orphan;
        });
  }

  private Profile orphanAtHome(AuthTestSupport.TestIdentity homeAdmin, String name) {
    return transactions.execute(
        _ -> {
          var orphan =
              profileRepository.saveAndFlush(
                  ProfileFixture.defaultProfileBuilder()
                      .householdId(homeAdmin.household().getId())
                      .name(name)
                      .build());
          managerRepository.saveAndFlush(
              ProfileManager.builder()
                  .accountId(homeAdmin.account().getId())
                  .profileId(orphan.getId())
                  .build());
          shareRepository.saveAndFlush(
              ProfileHouseholdShare.builder()
                  .profileId(orphan.getId())
                  .householdId(homeAdmin.household().getId())
                  .status(ProfileShareStatus.ACTIVE)
                  .build());
          return orphan;
        });
  }

  private ProfileHouseholdShare pendingShare(Profile profile, UUID householdId) {
    return shareRepository.saveAndFlush(
        ProfileHouseholdShare.builder()
            .profileId(profile.getId())
            .householdId(householdId)
            .status(ProfileShareStatus.PENDING)
            .offeredByAccountId(sourceAdmin.account().getId())
            .expiresAt(Instant.now().plus(Duration.ofDays(7)))
            .build());
  }

  private ProfileHouseholdShare activeShare(Profile profile, UUID householdId) {
    return shareRepository.saveAndFlush(
        ProfileHouseholdShare.builder()
            .profileId(profile.getId())
            .householdId(householdId)
            .status(ProfileShareStatus.ACTIVE)
            .build());
  }

  private String pendingLinkInvitation(Profile profile, String recipientEmail) {
    return pendingLinkInvitation(
        pendingLinkInvitationBuilder(profile, recipientEmail, sourceAdmin));
  }

  private String pendingLinkInvitation(
      Profile profile, String recipientEmail, AuthTestSupport.TestIdentity homeAdmin) {
    return pendingLinkInvitation(pendingLinkInvitationBuilder(profile, recipientEmail, homeAdmin));
  }

  private String pendingLinkInvitation(
      AccountInvitation.AccountInvitationBuilder<?, ?> invitationBuilder) {
    var issued = opaqueCodes.issue();
    transactions.executeWithoutResult(
        _ ->
            invitationRepository.saveAndFlush(
                invitationBuilder
                    .publicId(issued.publicId())
                    .secretDigest(issued.digest())
                    .build()));
    return issued.code();
  }

  private AccountInvitation.AccountInvitationBuilder<?, ?> pendingLinkInvitationBuilder(
      Profile profile, String recipientEmail, AuthTestSupport.TestIdentity homeAdmin) {
    return AccountInvitation.builder()
        .recipientEmail(recipientEmail)
        .householdId(homeAdmin.household().getId())
        .householdName(homeAdmin.household().getName())
        .householdRole(HouseholdRole.MEMBER)
        .mode(AccountInvitationMode.LINK)
        .profileId(profile.getId())
        .profileName(profile.getName())
        .profileKind(ProfileKind.ADULT)
        .issuerAccountId(homeAdmin.account().getId())
        .expiresAt(Instant.now().plus(Duration.ofDays(7)));
  }

  private void installAccountInsertBarrier() throws Exception {
    removeAccountInsertBarrier();
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute(
          """
          CREATE FUNCTION block_link_account_insert()
              RETURNS TRIGGER
              LANGUAGE plpgsql
          AS $$
          BEGIN
              PERFORM pg_advisory_xact_lock(
                  hashtextextended('test-link-account-insert', 0));
              RETURN NEW;
          END;
          $$
          """);
      statement.execute(
          """
          CREATE TRIGGER block_link_account_insert
          BEFORE INSERT ON user_account
          FOR EACH ROW
          WHEN (NEW.email IN ('%s', '%s'))
          EXECUTE FUNCTION block_link_account_insert()
          """
              .formatted(CROSS_HOME_FIRST_EMAIL, CROSS_HOME_SECOND_EMAIL));
    }
  }

  private Connection holdAccountInsertBarrier() throws Exception {
    var connection = dataSource.getConnection();
    connection.setAutoCommit(false);
    try (var statement = connection.createStatement()) {
      statement
          .executeQuery(
              """
              SELECT pg_advisory_xact_lock(
                  hashtextextended('test-link-account-insert', 0))
              """)
          .close();
    } catch (Exception failure) {
      connection.close();
      throw failure;
    }

    return connection;
  }

  private void removeAccountInsertBarrier() throws Exception {
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute("DROP TRIGGER IF EXISTS block_link_account_insert ON user_account");
      statement.execute("DROP FUNCTION IF EXISTS block_link_account_insert()");
    }
  }

  private IssueInvitationForProfileCommand linkInvitationCommand(
      Profile profile, String recipientEmail) {
    return IssueInvitationForProfileCommand.builder()
        .recipientEmail(recipientEmail)
        .householdRole(HouseholdRole.MEMBER)
        .profileId(profile.getId())
        .build();
  }

  private Connection holdInvitationIssuanceLock(String recipientEmail) throws Exception {
    var connection = dataSource.getConnection();
    connection.setAutoCommit(false);
    try (var statement =
        connection.prepareStatement(
            """
            SELECT pg_advisory_xact_lock(
                hashtextextended('account-invitation:' || lower(?), 0))
            """)) {
      statement.setString(1, recipientEmail);
      statement.executeQuery().close();
    } catch (Exception failure) {
      connection.close();
      throw failure;
    }

    return connection;
  }

  private Connection holdAuthSessionLock(UUID sessionId) throws Exception {
    var connection = dataSource.getConnection();
    connection.setAutoCommit(false);
    try (var statement =
        connection.prepareStatement("SELECT id FROM auth_session WHERE id = ? FOR UPDATE")) {
      statement.setObject(1, sessionId);
      statement.executeQuery().close();
    } catch (Exception failure) {
      connection.close();
      throw failure;
    }

    return connection;
  }

  private void installShareInsertBarrier(UUID profileId, UUID householdId) throws Exception {
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute(
          """
          CREATE FUNCTION block_link_share_offer_insert()
              RETURNS TRIGGER
              LANGUAGE plpgsql
          AS $$
          BEGIN
              PERFORM pg_advisory_xact_lock(
                  hashtextextended('test-link-share-offer-insert', 0));
              RETURN NEW;
          END;
          $$
          """);
      statement.execute(
          """
          CREATE TRIGGER block_link_share_offer_insert
          BEFORE INSERT ON profile_household_share
          FOR EACH ROW
          WHEN (NEW.profile_id = '%s'::uuid AND NEW.household_id = '%s'::uuid)
          EXECUTE FUNCTION block_link_share_offer_insert()
          """
              .formatted(profileId, householdId));
    }
  }

  private Connection holdShareInsertBarrier() throws Exception {
    var connection = dataSource.getConnection();
    connection.setAutoCommit(false);
    try (var statement = connection.createStatement()) {
      statement
          .executeQuery(
              """
              SELECT pg_advisory_xact_lock(
                  hashtextextended('test-link-share-offer-insert', 0))
              """)
          .close();
    } catch (Exception failure) {
      connection.close();
      throw failure;
    }

    return connection;
  }

  private void removeShareInsertBarrier() throws Exception {
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute(
          "DROP TRIGGER IF EXISTS block_link_share_offer_insert ON profile_household_share");
      statement.execute("DROP FUNCTION IF EXISTS block_link_share_offer_insert()");
    }
  }

  private static KeysetPaginationOptions paginationOptions() {
    return new KeysetPaginationOptions(
        null,
        PaginationOptions.builder()
            .paginationDirection(PaginationDirection.FORWARD)
            .cursor(Optional.empty())
            .limit(100)
            .build());
  }

  private static MediaPaginationOptions invitationPaginationOptions() {
    return MediaPaginationOptions.builder()
        .paginationOptions(
            PaginationOptions.builder()
                .paginationDirection(PaginationDirection.FORWARD)
                .cursor(Optional.empty())
                .limit(100)
                .build())
        .mediaFilter(
            MediaFilter.builder().sortBy(OrderMediaBy.ADDED).sortDirection(SortOrder.DESC).build())
        .build();
  }

  private static AcceptInvitationCommand acceptCommand(String code) {
    return AcceptInvitationCommand.builder()
        .code(code)
        .displayName("Joe")
        .password("a strong passphrase")
        .deviceName("web")
        .build();
  }

  private static AuthenticatedIdentity accountIdentity(AuthTestSupport.TestIdentity identity) {
    return AuthenticatedIdentity.builder()
        .accountId(identity.account().getId())
        .authSessionId(identity.session().getId())
        .scope(TokenScope.ACCOUNT)
        .householdId(identity.household().getId())
        .householdRole(identity.account().getHouseholdRole())
        .contextHouseholdId(identity.household().getId())
        .build();
  }

  private void collect(
      Future<AcceptedInvitation> attempt,
      List<AcceptedInvitation> successes,
      List<Throwable> failures)
      throws Exception {
    try {
      successes.add(attempt.get(15, TimeUnit.SECONDS));
    } catch (ExecutionException exception) {
      failures.add(exception.getCause());
    }
  }

  private void deleteLinkedAccount(String email) {
    accountRepository
        .findByEmailIgnoreCase(email)
        .ifPresent(account -> authTestSupport.deleteAccount(account.getId()));
  }

  private int blockedConnectionCount(int blockerPid) {
    return queryCount(
        """
        WITH RECURSIVE blocked(pid) AS (
            SELECT activity.pid
            FROM pg_stat_activity AS activity
            WHERE ? = ANY(pg_blocking_pids(activity.pid))
          UNION
            SELECT activity.pid
            FROM pg_stat_activity AS activity
            JOIN blocked AS blocker
              ON blocker.pid = ANY(pg_blocking_pids(activity.pid))
        )
        SELECT count(*) FROM blocked
        """,
        blockerPid);
  }

  private int backendPid(Connection connection) {
    try (var statement = connection.createStatement();
        var result = statement.executeQuery("SELECT pg_backend_pid()")) {
      result.next();
      return result.getInt(1);
    } catch (Exception exception) {
      throw new AssertionError("could not identify PostgreSQL connection", exception);
    }
  }

  private int queryCount(String sql, Object... parameters) {
    try (var connection = dataSource.getConnection();
        var statement = connection.prepareStatement(sql)) {
      for (var index = 0; index < parameters.length; index++) {
        statement.setObject(index + 1, parameters[index]);
      }

      try (var result = statement.executeQuery()) {
        result.next();
        return result.getInt(1);
      }
    } catch (Exception exception) {
      throw new AssertionError("could not inspect PostgreSQL lock state", exception);
    }
  }
}
