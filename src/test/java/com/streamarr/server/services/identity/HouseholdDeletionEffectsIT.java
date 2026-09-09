package com.streamarr.server.services.identity;

import static com.streamarr.server.fixtures.AccountInvitationFixture.pendingInvitationBuilder;
import static com.streamarr.server.jooq.generated.tables.SecurityAuditEvent.SECURITY_AUDIT_EVENT;
import static com.streamarr.server.jooq.generated.tables.ServerBootstrap.SERVER_BOOTSTRAP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.AccountInvitationStatus;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.DeviceRegistration;
import com.streamarr.server.domain.auth.DeviceRegistrationStatus;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.streaming.SessionProgress;
import com.streamarr.server.domain.streaming.WatchHistory;
import com.streamarr.server.exceptions.InvalidOneTimeCodeException;
import com.streamarr.server.exceptions.InvalidRefreshTokenException;
import com.streamarr.server.fixtures.HouseholdFixture;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.auth.AccountInvitationRepository;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.DeviceRegistrationRepository;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerInvitationRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.streaming.SessionProgressRepository;
import com.streamarr.server.repositories.streaming.WatchHistoryRepository;
import com.streamarr.server.services.auth.PasswordResetService;
import com.streamarr.server.services.auth.RefreshResult;
import com.streamarr.server.services.auth.RefreshTokenService;
import com.streamarr.server.services.identity.HouseholdDeletionService.DeleteLastAccountAndHouseholdCommand;
import com.streamarr.server.services.identity.HouseholdDeletionService.DeleteLastAccountAndHouseholdPreservingPersonalProfileCommand;
import com.streamarr.server.services.identity.HouseholdDeletionService.SecurityAuditPageRequest;
import com.streamarr.server.services.identity.HouseholdDeletionService.TransferLastAccountAndDeleteHouseholdCommand;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.pagination.PaginationDirection;
import com.streamarr.server.support.AuthTestSupport;
import java.time.Instant;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Household Deletion Effects Integration Tests")
@Isolated("Cleans shared PostgreSQL identity fixtures")
class HouseholdDeletionEffectsIT extends AbstractIntegrationTest {
  @Autowired private HouseholdDeletionService service;
  @Autowired private RefreshTokenService refreshTokens;
  @Autowired private CredentialIssuanceService credentialIssuance;
  @Autowired private PasswordResetService passwordReset;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private WatchHistoryRepository watchHistory;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private AuthSessionRepository authSessionRepository;
  @Autowired private HouseholdRepository householdRepository;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private ProfileManagerRepository profileManagerRepository;
  @Autowired private AccountInvitationRepository accountInvitationRepository;
  @Autowired private ProfileManagerInvitationRepository profileManagerInvitationRepository;
  @Autowired private DeviceRegistrationRepository registrationRepository;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MediaFileRepository mediaFileRepository;
  @Autowired private SessionProgressRepository sessionProgressRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DSLContext dsl;

  private AuthTestSupport.TestIdentity admin;
  private AuthTestSupport.TestIdentity doomed;

  @BeforeEach
  void setUp() {
    dsl.deleteFrom(SECURITY_AUDIT_EVENT).execute();
    admin = authTestSupport.createAdminIdentity();
    doomed = authTestSupport.createIdentity();
  }

  @AfterEach
  void tearDown() {
    dsl.deleteFrom(SERVER_BOOTSTRAP).execute();
    dsl.deleteFrom(SECURITY_AUDIT_EVENT).execute();
    profileManagerInvitationRepository.deleteAll();
    accountInvitationRepository.deleteAll();
    registrationRepository.deleteAll();
    if (householdRepository.findById(doomed.household().getId()).isPresent()) {
      authTestSupport.deleteIdentity(doomed);
    }

    authTestSupport.deleteIdentity(admin);
    mediaFileRepository.deleteAll();
    libraryRepository.deleteAll();
  }

  @Test
  @DisplayName("Should erase the Account, Profile and private data when deleting the final Account")
  void shouldEraseAccountProfileAndPrivateDataWhenDeletingFinalAccount() {
    var viewing = seedViewingData();

    performDisposition(FinalDisposition.DELETE, admin.household().getId());

    assertThat(userAccountRepository.findById(doomed.account().getId())).isEmpty();
    assertThat(profileRepository.findById(doomed.profile().getId())).isEmpty();
    assertThat(sessionProgressRepository.findById(viewing.progress().getId())).isEmpty();
    assertThat(watchHistory.findById(viewing.watched().getId())).isEmpty();
    assertThat(authSessionRepository.findById(doomed.session().getId())).isEmpty();
  }

  @Test
  @DisplayName("Should retain the Profile and private data when deleting with Profile preservation")
  void shouldRetainProfileAndPrivateDataWhenDeletingWithProfilePreservation() {
    var viewing = seedViewingData();

    performDisposition(FinalDisposition.PRESERVE, admin.household().getId());

    assertThat(userAccountRepository.findById(doomed.account().getId())).isEmpty();
    assertThat(authSessionRepository.findById(doomed.session().getId())).isEmpty();
    assertRetainedProfile(viewing, admin.household().getId());
    assertThat(
            profileManagerRepository.existsByAccountIdAndProfileId(
                admin.account().getId(), doomed.profile().getId()))
        .isTrue();
    assertThat(
            shareRepository.findByProfileIdAndHouseholdIdAndStatus(
                doomed.profile().getId(), admin.household().getId(), ProfileShareStatus.ACTIVE))
        .get()
        .extracting(ProfileHouseholdShare::isStructural)
        .isEqualTo(false);
  }

  @ParameterizedTest
  @EnumSource(
      value = FinalDisposition.class,
      names = {"TRANSFER_TO_EMPTY", "TRANSFER_TO_OCCUPIED"})
  @DisplayName(
      "Should move the Account and private Profile data when transferring the final Account")
  void shouldMoveAccountAndPrivateProfileDataWhenTransferringFinalAccount(
      FinalDisposition disposition) {
    var viewing = seedViewingData();
    var destination =
        disposition == FinalDisposition.TRANSFER_TO_EMPTY
            ? householdRepository.saveAndFlush(HouseholdFixture.defaultHouseholdBuilder().build())
            : admin.household();
    try {
      performDisposition(disposition, destination.getId());

      assertThat(userAccountRepository.findById(doomed.account().getId()))
          .get()
          .satisfies(
              account -> {
                assertThat(account.getHouseholdId()).isEqualTo(destination.getId());
                assertThat(account.getHouseholdRole())
                    .isEqualTo(
                        disposition == FinalDisposition.TRANSFER_TO_EMPTY
                            ? HouseholdRole.ADMIN
                            : HouseholdRole.MEMBER);
              });
      assertRetainedProfile(viewing, destination.getId());
      assertThat(
              shareRepository.findByProfileIdAndHouseholdIdAndStatus(
                  doomed.profile().getId(), destination.getId(), ProfileShareStatus.ACTIVE))
          .get()
          .extracting(ProfileHouseholdShare::isStructural)
          .isEqualTo(true);
      assertThat(authSessionRepository.findById(doomed.session().getId()))
          .get()
          .satisfies(
              session -> {
                assertThat(session.getRevokedAt()).isNull();
                assertThat(session.getContextHouseholdId()).isNull();
                assertThat(session.getSelectedProfileId()).isNull();
              });
    } finally {
      if (disposition == FinalDisposition.TRANSFER_TO_EMPTY) {
        authTestSupport.deleteIdentity(doomed);
      }
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = FinalDisposition.class,
      names = {"DELETE", "PRESERVE", "TRANSFER_TO_OCCUPIED"})
  @DisplayName("Should record the exact actor and disposition when final Account deletion commits")
  void shouldRecordExactActorAndDispositionWhenFinalAccountDeletionCommits(
      FinalDisposition disposition) {
    var before = databaseNow();

    performDisposition(disposition, admin.household().getId());

    assertThat(
            service
                .securityAuditEvents(
                    authTestSupport.freshIdentityOf(admin),
                    SecurityAuditPageRequest.builder()
                        .direction(PaginationDirection.FORWARD)
                        .limit(10)
                        .build())
                .items())
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.item().operation()).isEqualTo(disposition.operation);
              assertThat(item.item().actorAccountId()).isEqualTo(admin.account().getId());
              assertThat(item.item().reason()).isEqualTo("closing");
              assertThat(item.item().outcome()).isEqualTo("SUCCESS");
              assertThat(item.item().resources())
                  .isEqualTo("{\"householdId\": \"" + doomed.household().getId() + "\"}");
              assertThat(item.item().occurredAt()).isBetween(before, databaseNow());
            });
  }

  private void performDisposition(FinalDisposition disposition, UUID destinationId) {
    var identity = authTestSupport.freshIdentityOf(admin);
    var outcome =
        switch (disposition) {
          case DELETE ->
              service.deleteLastAccountAndHousehold(
                  identity,
                  DeleteLastAccountAndHouseholdCommand.builder()
                      .householdId(doomed.household().getId())
                      .reason("closing")
                      .build());
          case PRESERVE ->
              service.deleteLastAccountAndHouseholdPreservingPersonalProfile(
                  identity, preservationCommand().destinationHouseholdId(destinationId).build());
          case TRANSFER_TO_EMPTY, TRANSFER_TO_OCCUPIED ->
              service.transferLastAccountAndDeleteHousehold(
                  identity,
                  TransferLastAccountAndDeleteHouseholdCommand.builder()
                      .householdId(doomed.household().getId())
                      .destinationHouseholdId(destinationId)
                      .reason("closing")
                      .build());
        };
    assertThat(outcome).isEqualTo(Outcome.accepted(doomed.household().getId()));
    assertThat(householdRepository.findById(doomed.household().getId())).isEmpty();
  }

  private void assertRetainedProfile(ViewingData viewing, UUID destinationId) {
    assertThat(profileRepository.findById(doomed.profile().getId()))
        .get()
        .extracting(Profile::getHouseholdId)
        .isEqualTo(destinationId);
    assertThat(sessionProgressRepository.findById(viewing.progress().getId()))
        .get()
        .satisfies(
            saved -> {
              assertThat(saved.getProfileId()).isEqualTo(doomed.profile().getId());
              assertThat(saved.getPositionSeconds()).isEqualTo(120);
            });
    assertThat(watchHistory.findById(viewing.watched().getId()))
        .get()
        .extracting(WatchHistory::getWatchedAt)
        .isEqualTo(viewing.watched().getWatchedAt());
  }

  private ViewingData seedViewingData() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var media =
        mediaFileRepository.saveAndFlush(
            MediaFile.builder()
                .libraryId(library.getId())
                .status(MediaFileStatus.MATCHED)
                .filename("history.mkv")
                .filepathUri("file:///media/" + UUID.randomUUID() + "/history.mkv")
                .build());
    var progress =
        sessionProgressRepository.saveAndFlush(
            SessionProgress.builder()
                .profileId(doomed.profile().getId())
                .sessionId(UUID.randomUUID())
                .mediaFileId(media.getId())
                .positionSeconds(120)
                .percentComplete(20)
                .durationSeconds(600)
                .build());
    var watched =
        watchHistory.saveAndFlush(
            WatchHistory.builder()
                .profileId(doomed.profile().getId())
                .collectableId(UUID.randomUUID())
                .watchedAt(Instant.parse("2026-08-01T12:00:00Z"))
                .durationSeconds(600)
                .build());
    return new ViewingData(progress, watched);
  }

  private record ViewingData(SessionProgress progress, WatchHistory watched) {}

  private Instant databaseNow() {
    return dsl.select(DSL.currentOffsetDateTime()).fetchSingle().value1().toInstant();
  }

  private enum FinalDisposition {
    DELETE("deleteLastAccountAndHousehold"),
    PRESERVE("deleteLastAccountAndHouseholdPreservingPersonalProfile"),
    TRANSFER_TO_EMPTY("transferLastAccountAndDeleteHousehold"),
    TRANSFER_TO_OCCUPIED("transferLastAccountAndDeleteHousehold");
    private final String operation;

    FinalDisposition(String operation) {
      this.operation = operation;
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = FinalDisposition.class,
      names = {"DELETE", "PRESERVE"})
  @DisplayName("Should roll back deletion when it would delete the last enabled ServerAdmin")
  void shouldRollBackDeletionWhenItWouldDeleteLastEnabledServerAdmin(FinalDisposition disposition) {
    dsl.insertInto(SERVER_BOOTSTRAP)
        .set(SERVER_BOOTSTRAP.ADMIN_ACCOUNT_ID, admin.account().getId())
        .execute();

    var identity = authTestSupport.freshIdentityOf(admin);
    var outcome =
        disposition == FinalDisposition.DELETE
            ? service.deleteLastAccountAndHousehold(
                identity,
                DeleteLastAccountAndHouseholdCommand.builder()
                    .householdId(admin.household().getId())
                    .reason("closing")
                    .build())
            : service.deleteLastAccountAndHouseholdPreservingPersonalProfile(
                identity,
                DeleteLastAccountAndHouseholdPreservingPersonalProfileCommand.builder()
                    .householdId(admin.household().getId())
                    .destinationHouseholdId(doomed.household().getId())
                    .replacementManagerAccountId(doomed.account().getId())
                    .reason("closing")
                    .build());
    assertThat(outcome)
        .isEqualTo(Outcome.rejected(new HouseholdDeletionRejections.LastServerAdmin()));

    assertThat(householdRepository.findById(admin.household().getId())).isPresent();
    assertThat(userAccountRepository.findById(admin.account().getId())).isPresent();
    assertThat(profileRepository.findById(admin.profile().getId()))
        .get()
        .extracting(Profile::getHouseholdId)
        .isEqualTo(admin.household().getId());
    assertThat(
            shareRepository.findByProfileIdAndHouseholdIdAndStatus(
                admin.profile().getId(), admin.household().getId(), ProfileShareStatus.ACTIVE))
        .get()
        .extracting(share -> share.isStructural())
        .isEqualTo(true);
    assertThat(
            profileManagerRepository.existsByAccountIdAndProfileId(
                doomed.account().getId(), admin.profile().getId()))
        .isFalse();
    assertThat(authSessionRepository.findById(admin.session().getId()).orElseThrow().getRevokedAt())
        .isNull();
    assertThat(dsl.fetchCount(SECURITY_AUDIT_EVENT)).isZero();
  }

  @Test
  @DisplayName("Should remove only resident Profiles and their history when deleting a Household")
  void shouldRemoveOnlyResidentProfilesAndHistoryWhenDeletingHousehold() {
    var artifacts = seedDeletionArtifacts();
    var orphanHistory =
        watchHistory.saveAndFlush(viewingHistory().profileId(artifacts.orphanProfileId()).build());
    var visitorHistory =
        watchHistory.saveAndFlush(viewingHistory().profileId(admin.profile().getId()).build());
    var unrelatedProfile =
        transactionTemplate.execute(
            _ -> {
              var profile =
                  profileRepository.saveAndFlush(
                      ProfileFixture.defaultProfileBuilder()
                          .householdId(admin.household().getId())
                          .name("Unrelated unlinked Profile")
                          .build());
              profileManagerRepository.saveAndFlush(
                  ProfileManager.builder()
                      .profileId(profile.getId())
                      .accountId(admin.account().getId())
                      .build());
              return profile;
            });
    var unrelatedHistory =
        watchHistory.saveAndFlush(viewingHistory().profileId(unrelatedProfile.getId()).build());

    performSuccessfulTransferAndDeletion();

    assertThat(profileRepository.findById(artifacts.orphanProfileId())).isEmpty();
    assertThat(watchHistory.findById(orphanHistory.getId())).isEmpty();
    assertThat(shareRepository.findById(artifacts.hostedVisitId())).isEmpty();
    assertThat(profileRepository.findById(admin.profile().getId())).isPresent();
    assertThat(watchHistory.findById(visitorHistory.getId())).isPresent();
    assertThat(profileRepository.findById(unrelatedProfile.getId())).isPresent();
    assertThat(watchHistory.findById(unrelatedHistory.getId())).isPresent();
  }

  @Test
  @DisplayName("Should invalidate only pending invitations to the target when deleting a Household")
  void shouldInvalidateOnlyPendingInvitationsToTargetWhenDeletingHousehold() {
    var invitation =
        accountInvitationRepository.saveAndFlush(
            pendingInvitationBuilder()
                .householdName("Invitation target")
                .householdId(doomed.household().getId())
                .issuerAccountId(admin.account().getId())
                .build());
    var terminal =
        accountInvitationRepository.saveAndFlush(
            pendingInvitationBuilder()
                .householdName("Invitation target")
                .householdId(doomed.household().getId())
                .issuerAccountId(admin.account().getId())
                .status(AccountInvitationStatus.CANCELED)
                .decidedAt(Instant.parse("2026-08-01T12:00:00Z"))
                .build());
    var unrelated =
        accountInvitationRepository.saveAndFlush(
            pendingInvitationBuilder()
                .householdName("Invitation target")
                .householdId(admin.household().getId())
                .issuerAccountId(admin.account().getId())
                .build());

    performSuccessfulTransferAndDeletion();

    assertThat(accountInvitationRepository.findById(invitation.getId()))
        .get()
        .extracting(saved -> saved.getStatus())
        .isEqualTo(AccountInvitationStatus.INVALIDATED);
    assertThat(accountInvitationRepository.findById(terminal.getId()))
        .get()
        .satisfies(
            saved -> {
              assertThat(saved.getStatus()).isEqualTo(AccountInvitationStatus.CANCELED);
              assertThat(saved.getDecidedAt()).isEqualTo(terminal.getDecidedAt());
            });
    assertThat(accountInvitationRepository.findById(unrelated.getId()))
        .get()
        .extracting(saved -> saved.getStatus())
        .isEqualTo(AccountInvitationStatus.PENDING);
  }

  private WatchHistory.WatchHistoryBuilder<?, ?> viewingHistory() {
    return WatchHistory.builder()
        .collectableId(UUID.randomUUID())
        .watchedAt(Instant.parse("2026-08-01T12:00:00Z"))
        .durationSeconds(600);
  }

  @Test
  @DisplayName(
      "Should revoke remote registration sessions when deletion removes their authorizing Account")
  void shouldRevokeRemoteRegistrationSessionsWhenDeletionRemovesAuthorizingAccount() {
    var unrelatedRegistration =
        registrationRepository.saveAndFlush(
            DeviceRegistration.builder()
                .esn("surviving-admin-tv")
                .displayName("Admin TV")
                .householdId(admin.household().getId())
                .authorizingAccountId(admin.account().getId())
                .build());
    var unrelatedSession =
        authSessionRepository.saveAndFlush(
            AuthSession.builder()
                .accountId(admin.account().getId())
                .deviceName("Admin TV")
                .registrationId(unrelatedRegistration.getId())
                .contextHouseholdId(admin.household().getId())
                .selectedProfileId(admin.profile().getId())
                .build());
    var artifacts =
        transactionTemplate.execute(
            _ -> {
              shareRepository.saveAndFlush(
                  ProfileHouseholdShare.builder()
                      .profileId(doomed.profile().getId())
                      .householdId(admin.household().getId())
                      .status(ProfileShareStatus.ACTIVE)
                      .build());
              var registration =
                  registrationRepository.saveAndFlush(
                      DeviceRegistration.builder()
                          .esn("remote-final-account")
                          .displayName("Remote TV")
                          .householdId(admin.household().getId())
                          .authorizingAccountId(doomed.account().getId())
                          .build());
              var session =
                  authSessionRepository.saveAndFlush(
                      AuthSession.builder()
                          .accountId(admin.account().getId())
                          .deviceName("Remote TV")
                          .registrationId(registration.getId())
                          .contextHouseholdId(admin.household().getId())
                          .build());
              return new RemoteRegistrationArtifacts(registration.getId(), session.getId());
            });

    assertThat(
            service.deleteLastAccountAndHousehold(
                authTestSupport.freshIdentityOf(admin),
                DeleteLastAccountAndHouseholdCommand.builder()
                    .householdId(doomed.household().getId())
                    .reason("closing")
                    .build()))
        .isEqualTo(Outcome.accepted(doomed.household().getId()));

    assertThat(
            registrationRepository.findById(artifacts.registrationId()).orElseThrow().getStatus())
        .isEqualTo(DeviceRegistrationStatus.REVOKED);
    assertThat(
            authSessionRepository.findById(artifacts.sessionId()).orElseThrow().getRevokedReason())
        .isEqualTo(SessionRevocationReason.ADMIN_REVOCATION);
    assertThat(registrationRepository.findById(unrelatedRegistration.getId()))
        .get()
        .extracting(DeviceRegistration::getStatus)
        .isEqualTo(DeviceRegistrationStatus.ACTIVE);
    assertThat(authSessionRepository.findById(unrelatedSession.getId()))
        .get()
        .satisfies(
            session -> {
              assertThat(session.getRevokedAt()).isNull();
              assertThat(session.getContextHouseholdId()).isEqualTo(admin.household().getId());
              assertThat(session.getSelectedProfileId()).isEqualTo(admin.profile().getId());
            });
  }

  @Test
  @DisplayName("Should reset only visiting browser sessions when deleting their context Household")
  void shouldResetOnlyVisitingBrowserSessionsWhenDeletingContextHousehold() {
    shareRepository.saveAndFlush(
        ProfileHouseholdShare.builder()
            .profileId(admin.profile().getId())
            .householdId(doomed.household().getId())
            .status(ProfileShareStatus.ACTIVE)
            .build());
    var browser =
        authSessionRepository.saveAndFlush(
            adminSession().contextHouseholdId(doomed.household().getId()).build());
    var unrelated = authSessionRepository.saveAndFlush(adminSession().build());

    performSuccessfulTransferAndDeletion();

    assertThat(authSessionRepository.findById(browser.getId()))
        .get()
        .satisfies(
            session -> {
              assertThat(session.getContextHouseholdId()).isNull();
              assertThat(session.getSelectedProfileId()).isNull();
              assertThat(session.getRevokedAt()).isNull();
            });
    assertThat(authSessionRepository.findById(unrelated.getId()))
        .get()
        .satisfies(
            session -> {
              assertThat(session.getContextHouseholdId()).isEqualTo(admin.household().getId());
              assertThat(session.getSelectedProfileId()).isEqualTo(admin.profile().getId());
              assertThat(session.getRevokedAt()).isNull();
            });
  }

  @Test
  @DisplayName("Should revoke only hosted devices when deleting a Household")
  void shouldRevokeOnlyHostedDevicesWhenDeletingHousehold() {
    shareRepository.saveAndFlush(
        ProfileHouseholdShare.builder()
            .profileId(admin.profile().getId())
            .householdId(doomed.household().getId())
            .status(ProfileShareStatus.ACTIVE)
            .build());
    var hosted =
        registrationRepository.saveAndFlush(
            adminDevice().householdId(doomed.household().getId()).build());
    var hostedSession =
        authSessionRepository.saveAndFlush(
            adminSession()
                .registrationId(hosted.getId())
                .contextHouseholdId(doomed.household().getId())
                .selectedProfileId(null)
                .build());
    var unrelated = registrationRepository.saveAndFlush(adminDevice().build());
    var unrelatedSession =
        authSessionRepository.saveAndFlush(
            adminSession().registrationId(unrelated.getId()).build());

    performSuccessfulTransferAndDeletion();

    assertThat(registrationRepository.findById(hosted.getId()))
        .get()
        .extracting(DeviceRegistration::getStatus)
        .isEqualTo(DeviceRegistrationStatus.REVOKED);
    assertThat(authSessionRepository.findById(hostedSession.getId()))
        .get()
        .extracting(AuthSession::getRevokedReason)
        .isEqualTo(SessionRevocationReason.ADMIN_REVOCATION);
    assertThat(registrationRepository.findById(unrelated.getId()))
        .get()
        .extracting(DeviceRegistration::getStatus)
        .isEqualTo(DeviceRegistrationStatus.ACTIVE);
    assertThat(authSessionRepository.findById(unrelatedSession.getId()))
        .get()
        .extracting(AuthSession::getRevokedAt)
        .isNull();
  }

  @ParameterizedTest
  @EnumSource(
      value = FinalDisposition.class,
      names = {"DELETE", "PRESERVE"})
  @DisplayName("Should reject the removed Account's refresh token when deleting its Household")
  void shouldRejectRemovedAccountRefreshTokenWhenDeletingHousehold(FinalDisposition disposition) {
    var outcome =
        disposition == FinalDisposition.DELETE
            ? service.deleteLastAccountAndHousehold(
                authTestSupport.freshIdentityOf(admin),
                DeleteLastAccountAndHouseholdCommand.builder()
                    .householdId(doomed.household().getId())
                    .reason("closing")
                    .build())
            : service.deleteLastAccountAndHouseholdPreservingPersonalProfile(
                authTestSupport.freshIdentityOf(admin), preservationCommand().build());
    assertThat(outcome).isEqualTo(Outcome.accepted(doomed.household().getId()));

    var rawRefreshToken = doomed.rawRefreshToken();
    assertThatThrownBy(() -> refreshTokens.redeem(rawRefreshToken))
        .isInstanceOf(InvalidRefreshTokenException.class);
    assertThat(refreshTokens.redeem(admin.rawRefreshToken()))
        .isInstanceOf(RefreshResult.Rotated.class);
  }

  private AuthSession.AuthSessionBuilder<?, ?> adminSession() {
    return AuthSession.builder()
        .accountId(admin.account().getId())
        .deviceName("Admin browser")
        .contextHouseholdId(admin.household().getId())
        .selectedProfileId(admin.profile().getId());
  }

  private DeviceRegistration.DeviceRegistrationBuilder<?, ?> adminDevice() {
    return DeviceRegistration.builder()
        .esn(UUID.randomUUID().toString())
        .displayName("Admin TV")
        .householdId(admin.household().getId())
        .authorizingAccountId(admin.account().getId());
  }

  private DeleteLastAccountAndHouseholdPreservingPersonalProfileCommand
          .DeleteLastAccountAndHouseholdPreservingPersonalProfileCommandBuilder
      preservationCommand() {
    return DeleteLastAccountAndHouseholdPreservingPersonalProfileCommand.builder()
        .householdId(doomed.household().getId())
        .destinationHouseholdId(admin.household().getId())
        .replacementManagerAccountId(admin.account().getId())
        .reason("closing");
  }

  @Test
  @DisplayName("Should invalidate only removed Profile invitations when deleting a Household")
  void shouldInvalidateOnlyRemovedProfileInvitationsWhenDeletingHousehold() {
    var orphan = createSourceProfile();
    var target =
        accountInvitationRepository.saveAndFlush(
            pendingInvitationBuilder()
                .householdId(admin.household().getId())
                .householdName("Destination")
                .profileId(orphan.getId())
                .profileName(orphan.getName())
                .issuerAccountId(admin.account().getId())
                .build());
    var unrelated =
        accountInvitationRepository.saveAndFlush(
            pendingInvitationBuilder()
                .householdId(admin.household().getId())
                .householdName("Destination")
                .issuerAccountId(admin.account().getId())
                .build());

    performSuccessfulTransferAndDeletion();

    assertThat(accountInvitationRepository.findById(target.getId()))
        .get()
        .extracting(saved -> saved.getStatus())
        .isEqualTo(AccountInvitationStatus.INVALIDATED);
    assertThat(accountInvitationRepository.findById(unrelated.getId()))
        .get()
        .extracting(saved -> saved.getStatus())
        .isEqualTo(AccountInvitationStatus.PENDING);
  }

  @Test
  @DisplayName(
      "Should invalidate only removed Profile manager invitations when deleting a Household")
  void shouldInvalidateOnlyRemovedProfileManagerInvitationsWhenDeletingHousehold() {
    var orphan = createSourceProfile();
    var target =
        profileManagerInvitationRepository.saveAndFlush(
            managerInvitation().profileId(orphan.getId()).build());
    var unrelated =
        profileManagerInvitationRepository.saveAndFlush(
            managerInvitation().profileId(doomed.profile().getId()).build());

    performSuccessfulTransferAndDeletion();

    assertThat(profileManagerInvitationRepository.findById(target.getId()))
        .get()
        .extracting(ProfileManagerInvitation::getStatus)
        .isEqualTo(ProfileManagerInvitationStatus.INVALIDATED);
    assertThat(profileManagerInvitationRepository.findById(unrelated.getId()))
        .get()
        .extracting(ProfileManagerInvitation::getStatus)
        .isEqualTo(ProfileManagerInvitationStatus.PENDING);
  }

  @Test
  @DisplayName("Should remove only offers into the deleted Household when their Profile survives")
  void shouldRemoveOnlyOffersIntoDeletedHouseholdWhenProfileSurvives() {
    var visitor = authTestSupport.createIdentity();
    try {
      var target =
          shareRepository.saveAndFlush(
              ProfileHouseholdShare.builder()
                  .profileId(visitor.profile().getId())
                  .householdId(doomed.household().getId())
                  .offeredByAccountId(visitor.account().getId())
                  .status(ProfileShareStatus.PENDING)
                  .expiresAt(Instant.parse("2100-01-01T00:00:00Z"))
                  .build());
      var unrelated =
          shareRepository.saveAndFlush(
              ProfileHouseholdShare.builder()
                  .profileId(visitor.profile().getId())
                  .householdId(admin.household().getId())
                  .offeredByAccountId(visitor.account().getId())
                  .status(ProfileShareStatus.PENDING)
                  .expiresAt(Instant.parse("2100-01-01T00:00:00Z"))
                  .build());

      performSuccessfulTransferAndDeletion();

      assertThat(shareRepository.findById(target.getId())).isEmpty();
      assertThat(shareRepository.findById(unrelated.getId()))
          .get()
          .extracting(ProfileHouseholdShare::getStatus)
          .isEqualTo(ProfileShareStatus.PENDING);
      assertThat(profileRepository.findById(visitor.profile().getId())).isPresent();
    } finally {
      authTestSupport.deleteIdentity(visitor);
    }
  }

  @Test
  @DisplayName("Should keep a surviving manager's invitation when preserving the Profile")
  void shouldKeepSurvivingManagerInvitationWhenPreservingProfile() {
    var recipient = authTestSupport.createIdentity();
    try {
      profileManagerRepository.saveAndFlush(
          ProfileManager.builder()
              .accountId(admin.account().getId())
              .profileId(doomed.profile().getId())
              .build());
      var invitation =
          profileManagerInvitationRepository.saveAndFlush(
              managerInvitation()
                  .profileId(doomed.profile().getId())
                  .inviterAccountId(admin.account().getId())
                  .recipientAccountId(recipient.account().getId())
                  .recipientEmail(recipient.account().getEmail())
                  .build());

      assertThat(
              service.deleteLastAccountAndHouseholdPreservingPersonalProfile(
                  authTestSupport.freshIdentityOf(admin), preservationCommand().build()))
          .isEqualTo(Outcome.accepted(doomed.household().getId()));

      assertThat(profileManagerInvitationRepository.findById(invitation.getId()))
          .get()
          .extracting(ProfileManagerInvitation::getStatus)
          .isEqualTo(ProfileManagerInvitationStatus.PENDING);
    } finally {
      authTestSupport.deleteIdentity(recipient);
    }
  }

  @Test
  @DisplayName("Should keep a surviving manager's share offer when preserving the Profile")
  void shouldKeepSurvivingManagerShareOfferWhenPreservingProfile() {
    var recipient = authTestSupport.createIdentity();
    try {
      profileManagerRepository.saveAndFlush(
          ProfileManager.builder()
              .accountId(admin.account().getId())
              .profileId(doomed.profile().getId())
              .build());
      var offer =
          shareRepository.saveAndFlush(
              ProfileHouseholdShare.builder()
                  .profileId(doomed.profile().getId())
                  .householdId(recipient.household().getId())
                  .offeredByAccountId(admin.account().getId())
                  .status(ProfileShareStatus.PENDING)
                  .expiresAt(Instant.parse("2100-01-01T00:00:00Z"))
                  .build());

      assertThat(
              service.deleteLastAccountAndHouseholdPreservingPersonalProfile(
                  authTestSupport.freshIdentityOf(admin), preservationCommand().build()))
          .isEqualTo(Outcome.accepted(doomed.household().getId()));

      assertThat(shareRepository.findById(offer.getId()))
          .get()
          .extracting(ProfileHouseholdShare::getStatus)
          .isEqualTo(ProfileShareStatus.PENDING);
    } finally {
      authTestSupport.deleteIdentity(recipient);
    }
  }

  @Test
  @DisplayName("Should reject only the removed issuer's reset codes when deleting a Household")
  void shouldRejectOnlyRemovedIssuerResetCodesWhenDeletingHousehold() {
    var target = authTestSupport.createIdentity();
    var unrelated = authTestSupport.createIdentity();
    try {
      assertThat(userAccountRepository.tryGrantServerAdmin(doomed.account().getId())).isTrue();
      var code =
          acceptedResetCode(
              credentialIssuance.issuePasswordReset(
                  authTestSupport.freshIdentityOf(doomed),
                  target.account().getId(),
                  "recover access"));
      var unrelatedCode =
          acceptedResetCode(
              credentialIssuance.issuePasswordReset(
                  authTestSupport.freshIdentityOf(admin),
                  unrelated.account().getId(),
                  "recover access"));
      var oldHash = target.account().getPasswordHash();

      assertThat(
              service.deleteLastAccountAndHousehold(
                  authTestSupport.freshIdentityOf(admin),
                  DeleteLastAccountAndHouseholdCommand.builder()
                      .householdId(doomed.household().getId())
                      .reason("closing")
                      .build()))
          .isEqualTo(Outcome.accepted(doomed.household().getId()));

      assertThatThrownBy(() -> passwordReset.redeem(code, "a new password phrase"))
          .isInstanceOf(InvalidOneTimeCodeException.class);
      assertThat(userAccountRepository.findById(target.account().getId()))
          .get()
          .extracting(UserAccount::getPasswordHash)
          .isEqualTo(oldHash);
      assertThat(authSessionRepository.findById(target.session().getId()))
          .get()
          .extracting(AuthSession::getRevokedAt)
          .isNull();
      passwordReset.redeem(unrelatedCode, "a different password phrase");
      assertThat(
              passwordEncoder.matches(
                  "a different password phrase",
                  userAccountRepository
                      .findById(unrelated.account().getId())
                      .orElseThrow()
                      .getPasswordHash()))
          .isTrue();
    } finally {
      authTestSupport.deleteIdentity(target);
      authTestSupport.deleteIdentity(unrelated);
    }
  }

  private String acceptedResetCode(
      Outcome<CredentialIssuanceService.IssuedResetCode, CredentialRejections.IssueReset> outcome) {
    return switch (outcome) {
      case Outcome.Accepted<
              CredentialIssuanceService.IssuedResetCode, CredentialRejections.IssueReset>(
              var issued) ->
          issued.code();
      case Outcome.Rejected<
                  CredentialIssuanceService.IssuedResetCode, CredentialRejections.IssueReset>
              rejected ->
          throw new AssertionError("Reset issuance rejected: " + rejected);
    };
  }

  private ProfileManagerInvitation.ProfileManagerInvitationBuilder<?, ?> managerInvitation() {
    return ProfileManagerInvitation.builder()
        .profileName("Pending Profile")
        .inviterAccountId(doomed.account().getId())
        .inviterDisplayName(doomed.account().getDisplayName())
        .recipientAccountId(admin.account().getId())
        .recipientEmail(admin.account().getEmail())
        .expiresAt(Instant.parse("2100-01-01T00:00:00Z"))
        .publicId(UUID.randomUUID().toString())
        .secretDigest(new byte[32]);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("Should clear only removed Profile selections when deleting a Household")
  void shouldClearOnlyRemovedProfileSelectionsWhenDeletingHousehold(boolean revoked) {
    var orphan = createSourceProfile();
    var target =
        authSessionRepository.saveAndFlush(
            adminSession()
                .contextHouseholdId(doomed.household().getId())
                .selectedProfileId(orphan.getId())
                .revokedAt(revoked ? Instant.parse("2026-08-01T12:00:00Z") : null)
                .revokedReason(revoked ? SessionRevocationReason.ADMIN_REVOCATION : null)
                .build());
    var unrelated = authSessionRepository.saveAndFlush(adminSession().build());

    performSuccessfulTransferAndDeletion();

    assertThat(authSessionRepository.findById(target.getId()))
        .get()
        .satisfies(
            session -> {
              assertThat(session.getSelectedProfileId()).isNull();
              assertThat(session.getRevokedAt()).isEqualTo(target.getRevokedAt());
            });
    assertThat(authSessionRepository.findById(unrelated.getId()))
        .get()
        .satisfies(
            session -> {
              assertThat(session.getSelectedProfileId()).isEqualTo(admin.profile().getId());
              assertThat(session.getRevokedAt()).isNull();
            });
  }

  private Profile createSourceProfile() {
    return transactionTemplate.execute(
        _ -> {
          var profile =
              profileRepository.saveAndFlush(
                  ProfileFixture.defaultProfileBuilder()
                      .householdId(doomed.household().getId())
                      .name("Pending Profile")
                      .build());
          profileManagerRepository.saveAndFlush(
              ProfileManager.builder()
                  .accountId(doomed.account().getId())
                  .profileId(profile.getId())
                  .build());
          return profile;
        });
  }

  private DeletionArtifacts seedDeletionArtifacts() {
    return transactionTemplate.execute(
        _ -> {
          var orphan =
              profileRepository.saveAndFlush(
                  ProfileFixture.defaultProfileBuilder()
                      .householdId(doomed.household().getId())
                      .name("Orphan")
                      .build());
          profileManagerRepository.saveAndFlush(
              ProfileManager.builder()
                  .accountId(doomed.account().getId())
                  .profileId(orphan.getId())
                  .build());
          var visit =
              shareRepository.saveAndFlush(
                  ProfileHouseholdShare.builder()
                      .profileId(admin.account().getPersonalProfileId())
                      .householdId(doomed.household().getId())
                      .status(ProfileShareStatus.ACTIVE)
                      .build());
          var registration =
              registrationRepository.saveAndFlush(
                  DeviceRegistration.builder()
                      .esn("esn-doomed")
                      .displayName("TV")
                      .householdId(doomed.household().getId())
                      .authorizingAccountId(admin.account().getId())
                      .build());
          return new DeletionArtifacts(orphan.getId(), visit.getId(), registration.getId());
        });
  }

  private void performSuccessfulTransferAndDeletion() {
    assertThat(
            service.transferLastAccountAndDeleteHousehold(
                authTestSupport.freshIdentityOf(admin),
                TransferLastAccountAndDeleteHouseholdCommand.builder()
                    .householdId(doomed.household().getId())
                    .destinationHouseholdId(admin.household().getId())
                    .reason("closing shop")
                    .build()))
        .isEqualTo(Outcome.accepted(doomed.household().getId()));
  }

  private record DeletionArtifacts(
      UUID orphanProfileId, UUID hostedVisitId, UUID householdRegistrationId) {}

  private record RemoteRegistrationArtifacts(UUID registrationId, UUID sessionId) {}
}
