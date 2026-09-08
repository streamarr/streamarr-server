package com.streamarr.server.services.identity;

import static com.streamarr.server.jooq.generated.tables.SecurityAuditEvent.SECURITY_AUDIT_EVENT;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.streaming.SessionProgress;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.streaming.SessionProgressRepository;
import com.streamarr.server.services.auth.RefreshResult;
import com.streamarr.server.services.auth.RefreshTokenService;
import com.streamarr.server.services.identity.HouseholdDeletionService.DeleteLastAccountAndHouseholdPreservingPersonalProfileCommand;
import com.streamarr.server.services.identity.HouseholdDeletionService.HouseholdDeletionPreflightDetails;
import com.streamarr.server.services.identity.HouseholdDeletionService.TransferLastAccountAndDeleteHouseholdCommand;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.pagination.KeysetPaginationOptions;
import com.streamarr.server.services.pagination.PaginationDirection;
import com.streamarr.server.services.pagination.PaginationOptions;
import com.streamarr.server.support.AuthTestSupport;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Household Deletion Service Integration Tests")
@Isolated("Uses shared PostgreSQL identity fixtures")
class HouseholdDeletionServiceIT extends AbstractIntegrationTest {

  @Autowired private HouseholdDeletionService service;
  @Autowired private AuthSessionRepository sessions;
  @Autowired private ProfileHouseholdShareRepository shares;
  @Autowired private ProfileManagerRepository managers;
  @Autowired private RefreshTokenService refreshTokens;
  @Autowired private DSLContext dsl;
  @Autowired private AdministrationQueryService administration;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private SessionProgressRepository progressRepository;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MediaFileRepository mediaFileRepository;

  @Test
  @DisplayName(
      "Should return only the requested Profile's activity when another Profile has history")
  void shouldReturnOnlyRequestedProfileActivityWhenAnotherProfileHasHistory() {
    var admin = authTestSupport.createAdminIdentity();
    var source = authTestSupport.createIdentity();
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var media =
        mediaFileRepository.saveAndFlush(
            MediaFile.builder()
                .libraryId(library.getId())
                .status(MediaFileStatus.MATCHED)
                .filename("activity.mkv")
                .filepathUri("file:///media/" + UUID.randomUUID() + "/activity.mkv")
                .build());
    try {
      var selected =
          progressRepository.saveAndFlush(
              SessionProgress.builder()
                  .profileId(source.profile().getId())
                  .sessionId(UUID.randomUUID())
                  .mediaFileId(media.getId())
                  .durationSeconds(600)
                  .build());
      progressRepository.saveAndFlush(
          SessionProgress.builder()
              .profileId(admin.profile().getId())
              .sessionId(UUID.randomUUID())
              .mediaFileId(media.getId())
              .durationSeconds(600)
              .build());

      var page =
          service.profileActivity(
              authTestSupport.identityOf(admin),
              source.profile().getId(),
              new KeysetPaginationOptions(
                  null,
                  PaginationOptions.builder()
                      .paginationDirection(PaginationDirection.FORWARD)
                      .cursor(Optional.empty())
                      .limit(10)
                      .build()));

      assertThat(page.items())
          .extracting(item -> item.item().getId())
          .containsExactly(selected.getId());
    } finally {
      authTestSupport.deleteIdentity(source);
      authTestSupport.deleteIdentity(admin);
      mediaFileRepository.deleteById(media.getId());
      libraryRepository.deleteById(library.getId());
    }
  }

  @Test
  @DisplayName("Should reject and roll back transfer when the destination Profile name is taken")
  void shouldRejectAndRollBackTransferWhenDestinationProfileNameIsTaken() {
    assertNameConflictLeavesSourceIntact(ConflictDisposition.TRANSFER);
  }

  @Test
  @DisplayName(
      "Should reject and roll back preservation when the destination Profile name is taken")
  void shouldRejectAndRollBackPreservationWhenDestinationProfileNameIsTaken() {
    assertNameConflictLeavesSourceIntact(ConflictDisposition.PRESERVE);
  }

  private void assertNameConflictLeavesSourceIntact(ConflictDisposition disposition) {
    var admin = authTestSupport.createAdminIdentity();
    var source = authTestSupport.createIdentity();
    try {
      admin.profile().setName("Morgan");
      profileRepository.saveAndFlush(admin.profile());
      source.profile().setName("mOrGaN");
      profileRepository.saveAndFlush(source.profile());
      var identity = authTestSupport.freshIdentityOf(admin);

      Supplier<Outcome<UUID, HouseholdDeletionRejections.Delete>> delete =
          switch (disposition) {
            case TRANSFER ->
                () ->
                    service.transferLastAccountAndDeleteHousehold(
                        identity,
                        TransferLastAccountAndDeleteHouseholdCommand.builder()
                            .householdId(source.household().getId())
                            .destinationHouseholdId(admin.household().getId())
                            .reason("closing Household")
                            .build());
            case PRESERVE ->
                () ->
                    service.deleteLastAccountAndHouseholdPreservingPersonalProfile(
                        identity,
                        DeleteLastAccountAndHouseholdPreservingPersonalProfileCommand.builder()
                            .householdId(source.household().getId())
                            .destinationHouseholdId(admin.household().getId())
                            .replacementManagerAccountId(admin.account().getId())
                            .reason("closing Household")
                            .build());
          };
      var outcome = delete.get();

      assertThat(outcome)
          .isEqualTo(Outcome.rejected(new HouseholdDeletionRejections.NameConflict()));
      assertThat(service.deletionPreflight(identity, source.household().getId()))
          .get()
          .extracting(HouseholdDeletionPreflightDetails::accountCount)
          .isEqualTo(1);
      assertThat(administration.accountAdministration(identity, source.account().getId()))
          .get()
          .extracting(UserAccount::getHouseholdId)
          .isEqualTo(source.household().getId());
      assertThat(administration.profileAdministration(identity, source.profile().getId()))
          .get()
          .extracting(details -> details.profile().getHouseholdId())
          .isEqualTo(source.household().getId());
      assertThat(sessions.findById(source.session().getId()))
          .get()
          .extracting(session -> session.getRevokedAt())
          .isNull();
      assertThat(
              shares.findByProfileIdAndHouseholdIdAndStatus(
                  source.profile().getId(), source.household().getId(), ProfileShareStatus.ACTIVE))
          .get()
          .extracting(share -> share.isStructural())
          .isEqualTo(true);
      assertThat(
              managers.existsByAccountIdAndProfileId(
                  admin.account().getId(), source.profile().getId()))
          .isFalse();
      assertThat(
              dsl.fetchCount(
                  SECURITY_AUDIT_EVENT,
                  SECURITY_AUDIT_EVENT.ACTOR_ACCOUNT_ID.eq(admin.account().getId())))
          .isZero();
      assertThat(refreshTokens.redeem(source.rawRefreshToken()))
          .isInstanceOf(RefreshResult.Rotated.class);

      var corrected = profileRepository.findById(source.profile().getId()).orElseThrow();
      corrected.setName("Unique " + source.profile().getId());
      profileRepository.saveAndFlush(corrected);
      assertThat(delete.get()).isEqualTo(Outcome.accepted(source.household().getId()));
      assertThat(service.deletionPreflight(identity, source.household().getId())).isEmpty();
      assertThat(
              dsl.fetchCount(
                  SECURITY_AUDIT_EVENT,
                  SECURITY_AUDIT_EVENT.ACTOR_ACCOUNT_ID.eq(admin.account().getId())))
          .isEqualTo(1);
    } finally {
      authTestSupport.deleteIdentity(source);
      authTestSupport.deleteIdentity(admin);
    }
  }

  private enum ConflictDisposition {
    TRANSFER,
    PRESERVE
  }
}
