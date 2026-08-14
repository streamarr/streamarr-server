package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Portable Identity Concurrency Integration Tests")
class PortableIdentityConcurrencyIT extends AbstractIntegrationTest {

  @Autowired private PlatformTransactionManager transactionManager;

  @Autowired private HouseholdRepository householdRepository;

  @Autowired private UserAccountRepository accountRepository;

  @Autowired private ProfileRepository profileRepository;

  @Autowired private ProfileManagerRepository managerRepository;

  @Autowired private ProfileHouseholdShareRepository shareRepository;

  @ParameterizedTest(name = "Should serialize PIN removal and kid activation at {0}")
  @MethodSource("isolationLevels")
  @DisplayName("Should serialize PIN removal and kid activation at every supported isolation")
  void shouldSerializePinRemovalAndKidActivationAtEverySupportedIsolation(
      String isolationName, int isolationLevel, String expectedFailureState) throws Exception {
    var fixture = createFixture();
    var barrier = new CyclicBarrier(2);
    assertOneCommit(
        race(
            mutation(isolationLevel, () -> removeAdultPin(fixture.adultProfileId(), barrier)),
            mutation(isolationLevel, () -> activateKidShare(fixture, barrier))),
        expectedFailureState);

    var adult = profileRepository.findById(fixture.adultProfileId()).orElseThrow();
    var kidIsActive =
        shareRepository.existsByProfileIdAndHouseholdIdAndStatus(
            fixture.kidProfileId(), fixture.householdId(), ProfileShareStatus.ACTIVE);
    assertThat(adult.getPinHash() == null && kidIsActive).isFalse();
  }

  @ParameterizedTest(name = "Should serialize profile kind change and kid activation at {0}")
  @MethodSource("isolationLevels")
  @DisplayName(
      "Should serialize profile kind change and kid activation at every supported isolation")
  void shouldSerializeKindChangeAndKidActivationAtEverySupportedIsolation(
      String isolationName, int isolationLevel, String expectedFailureState) throws Exception {
    var fixture = createFixture(ProfileKind.KID, 7, null, "Kind Candidate");
    var barrier = new CyclicBarrier(2);
    assertOneCommit(
        race(
            mutation(isolationLevel, () -> changeKind(fixture.adultProfileId(), barrier)),
            mutation(isolationLevel, () -> activateKidShare(fixture, barrier))),
        expectedFailureState);

    var candidate = profileRepository.findById(fixture.adultProfileId()).orElseThrow();
    var kidIsActive =
        shareRepository.existsByProfileIdAndHouseholdIdAndStatus(
            fixture.kidProfileId(), fixture.householdId(), ProfileShareStatus.ACTIVE);
    assertThat(candidate.getKind() == ProfileKind.ADULT && kidIsActive).isFalse();
  }

  @ParameterizedTest(name = "Should serialize ceiling change and kid activation at {0}")
  @MethodSource("isolationLevels")
  @DisplayName("Should serialize ceiling change and kid activation at every supported isolation")
  void shouldSerializeCeilingChangeAndKidActivationAtEverySupportedIsolation(
      String isolationName, int isolationLevel, String expectedFailureState) throws Exception {
    var fixture = createFixture(ProfileKind.KID, 7, null, "Ceiling Candidate");
    var barrier = new CyclicBarrier(2);
    assertOneCommit(
        race(
            mutation(isolationLevel, () -> raiseCeiling(fixture.adultProfileId(), barrier)),
            mutation(isolationLevel, () -> activateKidShare(fixture, barrier))),
        expectedFailureState);

    var candidate = profileRepository.findById(fixture.adultProfileId()).orElseThrow();
    var kidIsActive =
        shareRepository.existsByProfileIdAndHouseholdIdAndStatus(
            fixture.kidProfileId(), fixture.householdId(), ProfileShareStatus.ACTIVE);
    assertThat(Integer.valueOf(16).equals(candidate.getMaximumAllowedRatingAge()) && kidIsActive)
        .isFalse();
  }

  @ParameterizedTest(name = "Should serialize manager removal and kid activation at {0}")
  @MethodSource("isolationLevels")
  @DisplayName("Should serialize manager removal and kid activation at every supported isolation")
  void shouldSerializeManagerRemovalAndKidActivationAtEverySupportedIsolation(
      String isolationName, int isolationLevel, String expectedFailureState) throws Exception {
    var fixture = createManagerFixture();
    var barrier = new CyclicBarrier(2);
    assertOneCommit(
        race(
            mutation(isolationLevel, () -> removeLocalManager(fixture, barrier)),
            mutation(isolationLevel, () -> activateKidShare(fixture, barrier))),
        expectedFailureState);

    var localManagerRemains =
        managerRepository.existsByAccountIdAndProfileId(
            fixture.localManagerAccountId(), fixture.kidProfileId());
    var kidIsActive =
        shareRepository.existsByProfileIdAndHouseholdIdAndStatus(
            fixture.kidProfileId(), fixture.householdId(), ProfileShareStatus.ACTIVE);
    assertThat(!localManagerRemains && kidIsActive).isFalse();
  }

  @Test
  @DisplayName("Should retain a manager when two co managers concurrently relinquish")
  void shouldRetainManagerWhenTwoCoManagersConcurrentlyRelinquish() throws Exception {
    var fixture = createRelinquishmentFixture();
    var barrier = new CyclicBarrier(2);

    assertOneCommit(
        race(
            mutation(
                TransactionDefinition.ISOLATION_READ_COMMITTED,
                () -> preflightRelinquish(fixture.profileId(), fixture.firstManagerId(), barrier)),
            mutation(
                TransactionDefinition.ISOLATION_READ_COMMITTED,
                () ->
                    preflightRelinquish(fixture.profileId(), fixture.secondManagerId(), barrier))),
        "23514");

    assertThat(managerRepository.countByProfileId(fixture.profileId())).isEqualTo(1);
  }

  private List<MutationResult> race(Callable<MutationResult> first, Callable<MutationResult> second)
      throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var firstFuture = executor.submit(first);
      var secondFuture = executor.submit(second);
      return List.of(firstFuture.get(30, TimeUnit.SECONDS), secondFuture.get(30, TimeUnit.SECONDS));
    }
  }

  private void assertOneCommit(List<MutationResult> results, String expectedFailureState) {
    assertThat(results).filteredOn(MutationResult::committed).hasSize(1);
    assertThat(results).filteredOn(result -> !result.committed()).hasSize(1);
    assertThat(
            results.stream()
                .filter(result -> !result.committed())
                .findFirst()
                .orElseThrow()
                .sqlState())
        .isEqualTo(expectedFailureState);
  }

  private Callable<MutationResult> mutation(int isolation, ThrowingRunnable mutation) {
    return () -> {
      var transaction = new TransactionTemplate(transactionManager);
      transaction.setIsolationLevel(isolation);
      try {
        transaction.executeWithoutResult(_ -> mutation.run());
        return new MutationResult(true, null);
      } catch (RuntimeException exception) {
        return new MutationResult(false, sqlState(exception));
      }
    };
  }

  private void removeAdultPin(UUID adultProfileId, CyclicBarrier barrier) {
    var adult = profileRepository.findById(adultProfileId).orElseThrow();
    adult.setPinHash(null);
    profileRepository.saveAndFlush(adult);
    await(barrier);
  }

  private void activateKidShare(ConcurrencyFixture fixture, CyclicBarrier barrier) {
    shareRepository.saveAndFlush(
        ProfileHouseholdShare.builder()
            .profileId(fixture.kidProfileId())
            .householdId(fixture.householdId())
            .status(ProfileShareStatus.ACTIVE)
            .build());
    await(barrier);
  }

  private void changeKind(UUID profileId, CyclicBarrier barrier) {
    var profile = profileRepository.findById(profileId).orElseThrow();
    profile.setKind(ProfileKind.ADULT);
    profileRepository.saveAndFlush(profile);
    await(barrier);
  }

  private void raiseCeiling(UUID profileId, CyclicBarrier barrier) {
    var profile = profileRepository.findById(profileId).orElseThrow();
    profile.setMaximumAllowedRatingAge(16);
    profileRepository.saveAndFlush(profile);
    await(barrier);
  }

  private void removeLocalManager(ConcurrencyFixture fixture, CyclicBarrier barrier) {
    var manager =
        managerRepository
            .findByAccountIdAndProfileId(fixture.localManagerAccountId(), fixture.kidProfileId())
            .orElseThrow();
    managerRepository.delete(manager);
    managerRepository.flush();
    await(barrier);
  }

  private void preflightRelinquish(UUID profileId, UUID accountId, CyclicBarrier barrier) {
    var manager = managerRepository.findByAccountIdAndProfileId(accountId, profileId).orElseThrow();
    if (managerRepository.countByProfileId(profileId) <= 1) {
      throw new IllegalStateException("Preflight did not observe another manager");
    }
    await(barrier);
    managerRepository.delete(manager);
    managerRepository.flush();
  }

  private ConcurrencyFixture createFixture() {
    return createFixture(ProfileKind.ADULT, null, "encoded-pin", "Protected Adult");
  }

  private ConcurrencyFixture createFixture(
      ProfileKind kind, Integer ceiling, String pinHash, String name) {
    return new TransactionTemplate(transactionManager)
        .execute(
            _ -> {
              var household =
                  householdRepository.save(
                      Household.builder().name("Concurrent Home " + UUID.randomUUID()).build());
              var parent =
                  accountRepository.save(
                      UserAccount.builder()
                          .email("concurrency-" + UUID.randomUUID() + "@example.com")
                          .displayName("Concurrent Parent")
                          .passwordHash("encoded")
                          .accountRole(AccountRole.USER)
                          .homeHouseholdId(household.getId())
                          .householdRole(HouseholdRole.OWNER)
                          .build());
              var adult =
                  profileRepository.save(
                      Profile.builder()
                          .name(name)
                          .kind(kind)
                          .maximumAllowedRatingAge(ceiling)
                          .pinHash(pinHash)
                          .build());
              var kid =
                  profileRepository.save(
                      Profile.builder()
                          .name("Concurrent Kid")
                          .kind(ProfileKind.KID)
                          .maximumAllowedRatingAge(7)
                          .build());
              managerRepository.save(
                  ProfileManager.builder()
                      .accountId(parent.getId())
                      .profileId(adult.getId())
                      .build());
              managerRepository.save(
                  ProfileManager.builder()
                      .accountId(parent.getId())
                      .profileId(kid.getId())
                      .build());
              shareRepository.save(
                  ProfileHouseholdShare.builder()
                      .profileId(adult.getId())
                      .householdId(household.getId())
                      .status(ProfileShareStatus.ACTIVE)
                      .build());
              return new ConcurrencyFixture(
                  household.getId(), adult.getId(), kid.getId(), parent.getId());
            });
  }

  private ConcurrencyFixture createManagerFixture() {
    return new TransactionTemplate(transactionManager)
        .execute(
            _ -> {
              var localHousehold =
                  householdRepository.save(
                      Household.builder().name("Local Manager Home " + UUID.randomUUID()).build());
              var localManager =
                  accountRepository.save(
                      UserAccount.builder()
                          .email("local-manager-" + UUID.randomUUID() + "@example.com")
                          .displayName("Local Manager")
                          .passwordHash("encoded")
                          .accountRole(AccountRole.USER)
                          .homeHouseholdId(localHousehold.getId())
                          .householdRole(HouseholdRole.OWNER)
                          .build());
              var remoteHousehold =
                  householdRepository.save(
                      Household.builder().name("Remote Manager Home " + UUID.randomUUID()).build());
              var remoteManager =
                  accountRepository.save(
                      UserAccount.builder()
                          .email("remote-manager-" + UUID.randomUUID() + "@example.com")
                          .displayName("Remote Manager")
                          .passwordHash("encoded")
                          .accountRole(AccountRole.USER)
                          .homeHouseholdId(remoteHousehold.getId())
                          .householdRole(HouseholdRole.OWNER)
                          .build());
              var kid =
                  profileRepository.save(
                      Profile.builder()
                          .name("Manager Race Kid")
                          .kind(ProfileKind.KID)
                          .maximumAllowedRatingAge(7)
                          .build());
              managerRepository.save(
                  ProfileManager.builder()
                      .accountId(localManager.getId())
                      .profileId(kid.getId())
                      .build());
              managerRepository.save(
                  ProfileManager.builder()
                      .accountId(remoteManager.getId())
                      .profileId(kid.getId())
                      .build());
              return new ConcurrencyFixture(
                  localHousehold.getId(), kid.getId(), kid.getId(), localManager.getId());
            });
  }

  private RelinquishmentFixture createRelinquishmentFixture() {
    return new TransactionTemplate(transactionManager)
        .execute(
            _ -> {
              var firstHousehold =
                  householdRepository.save(
                      Household.builder().name("First Manager Home " + UUID.randomUUID()).build());
              var firstManager =
                  accountRepository.save(
                      UserAccount.builder()
                          .email("first-manager-" + UUID.randomUUID() + "@example.com")
                          .displayName("First Manager")
                          .passwordHash("encoded")
                          .accountRole(AccountRole.USER)
                          .homeHouseholdId(firstHousehold.getId())
                          .householdRole(HouseholdRole.OWNER)
                          .build());
              var secondHousehold =
                  householdRepository.save(
                      Household.builder().name("Second Manager Home " + UUID.randomUUID()).build());
              var secondManager =
                  accountRepository.save(
                      UserAccount.builder()
                          .email("second-manager-" + UUID.randomUUID() + "@example.com")
                          .displayName("Second Manager")
                          .passwordHash("encoded")
                          .accountRole(AccountRole.USER)
                          .homeHouseholdId(secondHousehold.getId())
                          .householdRole(HouseholdRole.OWNER)
                          .build());
              var profile =
                  profileRepository.save(
                      Profile.builder().name("Relinquishment Race " + UUID.randomUUID()).build());
              managerRepository.save(
                  ProfileManager.builder()
                      .accountId(firstManager.getId())
                      .profileId(profile.getId())
                      .build());
              managerRepository.save(
                  ProfileManager.builder()
                      .accountId(secondManager.getId())
                      .profileId(profile.getId())
                      .build());
              return new RelinquishmentFixture(
                  profile.getId(), firstManager.getId(), secondManager.getId());
            });
  }

  private void await(CyclicBarrier barrier) {
    try {
      barrier.await(10, TimeUnit.SECONDS);
    } catch (Exception exception) {
      throw new IllegalStateException("Concurrent mutation barrier failed", exception);
    }
  }

  private String sqlState(Throwable failure) {
    for (var cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sqlException) {
        return sqlException.getSQLState();
      }
    }
    return null;
  }

  private static Stream<Arguments> isolationLevels() {
    return Stream.of(
        Arguments.of("READ COMMITTED", TransactionDefinition.ISOLATION_READ_COMMITTED, "23514"),
        Arguments.of("REPEATABLE READ", TransactionDefinition.ISOLATION_REPEATABLE_READ, "40001"),
        Arguments.of("SERIALIZABLE", TransactionDefinition.ISOLATION_SERIALIZABLE, "40001"));
  }

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run();
  }

  private record ConcurrencyFixture(
      UUID householdId, UUID adultProfileId, UUID kidProfileId, UUID localManagerAccountId) {}

  private record MutationResult(boolean committed, String sqlState) {}

  private record RelinquishmentFixture(UUID profileId, UUID firstManagerId, UUID secondManagerId) {}
}
