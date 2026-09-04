package com.streamarr.server.repositories.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.fixtures.HouseholdFixture;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.security.WithAccountContext;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.AuditorAware;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Profile Household Share Repository Custom Implementation Integration Tests")
@WithAccountContext
@Transactional
class ProfileHouseholdShareRepositoryCustomImplIT extends AbstractIntegrationTest {

  @Autowired private HouseholdRepository householdRepository;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private ProfileManagerRepository profileManagerRepository;
  @Autowired private AuditorAware<UUID> auditorAware;
  @Autowired private TransactionTemplate transactions;
  @Autowired private DataSource dataSource;
  @Autowired private AuthTestSupport authTestSupport;

  @Test
  @DisplayName("Should populate audit fields when inserting a structural home share")
  void shouldPopulateAuditFieldsWhenInsertingStructuralHomeShare() {
    var now = Instant.parse("2026-08-21T12:00:00Z");
    var ids =
        transactions.execute(
            _ -> {
              var household =
                  householdRepository.saveAndFlush(
                      HouseholdFixture.defaultHouseholdBuilder().build());
              var profile =
                  profileRepository.saveAndFlush(
                      ProfileFixture.defaultProfileBuilder()
                          .householdId(household.getId())
                          .build());
              shareRepository.ensureActiveMembershipShare(profile.getId(), household.getId(), now);
              return new ProfileHouseholdIds(profile.getId(), household.getId());
            });

    var share =
        shareRepository
            .findByProfileIdAndHouseholdIdAndStatus(
                ids.profileId(), ids.householdId(), ProfileShareStatus.ACTIVE)
            .orElseThrow();
    var expectedAuditor = auditorAware.getCurrentAuditor().orElseThrow();

    assertSoftly(
        softly -> {
          softly.assertThat(share.getCreatedOn()).isEqualTo(now);
          softly.assertThat(share.getLastModifiedOn()).isEqualTo(now);
          softly.assertThat(share.getCreatedBy()).isEqualTo(expectedAuditor);
          softly.assertThat(share.getLastModifiedBy()).isEqualTo(expectedAuditor);
        });
  }

  @Test
  @DisplayName("Should preserve an expired offer when creating a structural home share")
  void shouldPreserveExpiredOfferWhenCreatingStructuralHomeShare() {
    var now = Instant.parse("2026-08-21T12:00:00Z");
    var household =
        householdRepository.saveAndFlush(HouseholdFixture.defaultHouseholdBuilder().build());
    var profile =
        profileRepository.saveAndFlush(
            ProfileFixture.defaultProfileBuilder().householdId(household.getId()).build());
    var expiredOffer =
        shareRepository.saveAndFlush(
            ProfileHouseholdShare.builder()
                .profileId(profile.getId())
                .householdId(household.getId())
                .status(ProfileShareStatus.PENDING)
                .expiresAt(now.minusSeconds(1))
                .build());

    shareRepository.ensureActiveMembershipShare(profile.getId(), household.getId(), now);

    var preservedOffer =
        shareRepository.findByIdAndReloadFromDatabase(expiredOffer.getId()).orElseThrow();
    var structuralShare =
        shareRepository
            .findByProfileIdAndHouseholdIdAndStatus(
                profile.getId(), household.getId(), ProfileShareStatus.ACTIVE)
            .orElseThrow();
    assertSoftly(
        softly -> {
          softly.assertThat(preservedOffer.getStatus()).isEqualTo(ProfileShareStatus.EXPIRED);
          softly.assertThat(preservedOffer.getDecidedAt()).isEqualTo(now);
          softly.assertThat(structuralShare.getId()).isNotEqualTo(expiredOffer.getId());
          softly.assertThat(structuralShare.isStructural()).isTrue();
        });
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @DisplayName("Should preserve an expired offer when its offerer loses management")
  void shouldPreserveExpiredOfferWhenItsOffererLosesManagement() {
    var offerer = authTestSupport.createAdminIdentity();
    var recipient = authTestSupport.createAdminIdentity();
    var now = Instant.parse("2026-08-21T12:00:00Z");
    var expiredOffer =
        shareRepository.saveAndFlush(
            ProfileHouseholdShare.builder()
                .profileId(offerer.profile().getId())
                .householdId(recipient.household().getId())
                .status(ProfileShareStatus.PENDING)
                .offeredByAccountId(offerer.account().getId())
                .expiresAt(now.minusSeconds(1))
                .build());

    try {
      shareRepository.invalidatePendingOffersByProfileIdAndOffererAccountId(
          offerer.profile().getId(), offerer.account().getId(), "offerer left", now);

      assertThat(shareRepository.findById(expiredOffer.getId()).orElseThrow())
          .satisfies(
              offer -> {
                assertThat(offer.getStatus()).isEqualTo(ProfileShareStatus.EXPIRED);
                assertThat(offer.getDecidedAt()).isEqualTo(now);
                assertThat(offer.getInvalidationReason()).isEmpty();
              });
    } finally {
      shareRepository.deleteById(expiredOffer.getId());
      authTestSupport.deleteIdentity(recipient);
      authTestSupport.deleteIdentity(offerer);
    }
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @DisplayName("Should create one structural home Share when two writers race")
  void shouldCreateOneStructuralHomeShareWhenTwoWritersRace() throws Exception {
    var manager = authTestSupport.createAdminIdentity();
    try {
      var ids = createProfileHouseholdIds(manager);
      var failures = Collections.synchronizedList(new ArrayList<Throwable>());
      installMembershipShareInsertBarrier(ids);

      try (var executor = Executors.newVirtualThreadPerTaskExecutor();
          var insertBarrier = holdMembershipShareInsertBarrier()) {
        var first = executor.submit(() -> ensureMembershipShare(ids, failures));
        var second = executor.submit(() -> ensureMembershipShare(ids, failures));
        var blockerPid = backendPid(insertBarrier);
        try {
          await()
              .atMost(Duration.ofSeconds(5))
              .until(() -> blockedConnectionCount(blockerPid) == 2);
        } finally {
          insertBarrier.rollback();
        }

        first.get(5, TimeUnit.SECONDS);
        second.get(5, TimeUnit.SECONDS);
      } finally {
        removeMembershipShareInsertBarrier();
      }

      assertThat(failures).isEmpty();
      assertThat(
              shareRepository.findByProfileIdAndHouseholdIdAndStatus(
                  ids.profileId(), ids.householdId(), ProfileShareStatus.ACTIVE))
          .isPresent()
          .get()
          .extracting(ProfileHouseholdShare::isStructural)
          .isEqualTo(true);
      assertThat(shareRepository.findByProfileId(ids.profileId()))
          .filteredOn(share -> share.getStatus() == ProfileShareStatus.ACTIVE)
          .hasSize(1);
    } finally {
      authTestSupport.deleteIdentity(manager);
    }
  }

  private ProfileHouseholdIds createProfileHouseholdIds(AuthTestSupport.TestIdentity manager) {
    return transactions.execute(
        _ -> {
          var profile =
              profileRepository.saveAndFlush(
                  ProfileFixture.defaultProfileBuilder()
                      .householdId(manager.household().getId())
                      .build());
          profileManagerRepository.saveAndFlush(
              ProfileManager.builder()
                  .profileId(profile.getId())
                  .accountId(manager.account().getId())
                  .build());
          return new ProfileHouseholdIds(profile.getId(), manager.household().getId());
        });
  }

  private void ensureMembershipShare(ProfileHouseholdIds ids, List<Throwable> failures) {
    try {
      transactions.executeWithoutResult(
          _ ->
              shareRepository.ensureActiveMembershipShare(
                  ids.profileId(), ids.householdId(), Instant.now()));
    } catch (Throwable failure) {
      failures.add(failure);
    }
  }

  private void installMembershipShareInsertBarrier(ProfileHouseholdIds ids) throws Exception {
    removeMembershipShareInsertBarrier();
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute(
          """
          CREATE FUNCTION block_membership_share_insert()
              RETURNS TRIGGER
              LANGUAGE plpgsql
          AS $$
          BEGIN
              PERFORM pg_advisory_xact_lock(
                  hashtextextended('test-membership-share-insert', 0));
              RETURN NEW;
          END;
          $$
          """);
      statement.execute(
          """
          CREATE TRIGGER block_membership_share_insert
          BEFORE INSERT ON profile_household_share
          FOR EACH ROW
          WHEN (NEW.profile_id = '%s'::uuid AND NEW.household_id = '%s'::uuid)
          EXECUTE FUNCTION block_membership_share_insert()
          """
              .formatted(ids.profileId(), ids.householdId()));
    }
  }

  private Connection holdMembershipShareInsertBarrier() throws Exception {
    var connection = dataSource.getConnection();
    connection.setAutoCommit(false);
    try (var statement = connection.createStatement()) {
      statement
          .executeQuery(
              """
              SELECT pg_advisory_xact_lock(
                  hashtextextended('test-membership-share-insert', 0))
              """)
          .close();
    } catch (Exception failure) {
      connection.close();
      throw failure;
    }

    return connection;
  }

  private void removeMembershipShareInsertBarrier() throws Exception {
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute(
          "DROP TRIGGER IF EXISTS block_membership_share_insert ON profile_household_share");
      statement.execute("DROP FUNCTION IF EXISTS block_membership_share_insert()");
    }
  }

  private int blockedConnectionCount(int blockerPid) {
    try (var connection = dataSource.getConnection();
        var statement =
            connection.prepareStatement(
                """
                SELECT count(*)
                FROM pg_stat_activity
                WHERE ? = ANY(pg_blocking_pids(pid))
                """)) {
      statement.setInt(1, blockerPid);
      try (var result = statement.executeQuery()) {
        result.next();
        return result.getInt(1);
      }
    } catch (Exception failure) {
      throw new AssertionError("could not inspect PostgreSQL lock state", failure);
    }
  }

  private static int backendPid(Connection connection) {
    try (var statement = connection.createStatement();
        var result = statement.executeQuery("SELECT pg_backend_pid()")) {
      result.next();
      return result.getInt(1);
    } catch (Exception failure) {
      throw new AssertionError("could not identify PostgreSQL connection", failure);
    }
  }

  private record ProfileHouseholdIds(UUID profileId, UUID householdId) {}
}
