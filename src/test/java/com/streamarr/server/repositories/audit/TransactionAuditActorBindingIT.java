package com.streamarr.server.repositories.audit;

import static com.streamarr.server.jooq.generated.tables.Genre.GENRE;
import static com.streamarr.server.support.TestFixtureAuditActorConfiguration.TEST_FIXTURE_ACTOR_ID;
import static com.streamarr.server.support.security.SignedInAccount.callSignedInAs;
import static com.streamarr.server.support.security.SignedInAccount.runSignedInAs;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.SystemActor;
import com.streamarr.server.services.audit.SystemWork;
import com.streamarr.server.support.AuditActorSetting;
import com.streamarr.server.support.TestFixtureWork;
import com.streamarr.server.support.security.AnonymousRequestAuthentication;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Transaction Audit Actor Binding Integration Tests")
class TransactionAuditActorBindingIT extends AbstractIntegrationTest {

  private static final Duration THREAD_TIMEOUT = Duration.ofSeconds(10);

  @Autowired private DSLContext dsl;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private PlatformTransactionManager transactionManager;

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("Should expose the signed-in account when a transaction begins")
  void shouldExposeTheSignedInAccountWhenATransactionBegins() {
    var accountId = UUID.randomUUID();

    var actorSetting = callSignedInAs(accountId, () -> actorSettingIn(transactionTemplate));

    assertThat(actorSetting).isEqualTo(accountId.toString());
  }

  @Test
  @DisplayName("Should expose the System actor when system work begins a transaction")
  void shouldExposeTheSystemActorWhenSystemWorkBeginsATransaction() {
    var actorSetting = new AtomicReference<String>();

    SystemWork.runAsSystemActor(() -> actorSetting.set(actorSettingIn(transactionTemplate)));

    assertThat(actorSetting).hasValue(SystemActor.ID.toString());
  }

  @Test
  @DisplayName("Should expose the request-without-account marker when an anonymous request begins")
  void shouldExposeTheRequestWithoutAccountMarkerWhenAnAnonymousRequestBegins() {
    SecurityContextHolder.getContext().setAuthentication(AnonymousRequestAuthentication.token());

    assertThat(actorSettingIn(transactionTemplate))
        .isEqualTo(AuditActorSetting.REQUEST_WITHOUT_ACCOUNT);
  }

  @Test
  @DisplayName("Should expose no actor when the transaction is read-only")
  void shouldExposeNoActorWhenTheTransactionIsReadOnly() {
    var readOnly = new TransactionTemplate(transactionManager);
    readOnly.setReadOnly(true);

    var actorSetting = callSignedInAs(UUID.randomUUID(), () -> actorSettingIn(readOnly));

    assertThat(actorSetting).isNullOrEmpty();
  }

  @Test
  @DisplayName("Should expose the actor to a jOOQ transaction when it begins")
  void shouldExposeTheActorToAJooqTransactionWhenItBegins() {
    var accountId = UUID.randomUUID();

    var actorSetting =
        callSignedInAs(
            accountId,
            () -> dsl.transactionResult(configuration -> actorSetting(configuration.dsl())));

    assertThat(actorSetting).isEqualTo(accountId.toString());
  }

  @ParameterizedTest(name = "{0} thread")
  @EnumSource(ThreadKind.class)
  @DisplayName(
      "Should reject an audited write when a thread the test did not start as the fixture actor"
          + " begins a transaction")
  void shouldRejectAnAuditedWriteWhenAThreadTheTestDidNotStartAsTheFixtureActorBeginsATransaction(
      ThreadKind threadKind) {
    var sourceId = "unattributed-" + UUID.randomUUID();

    var write =
        runOnNewThread(
            threadKind, () -> transactionTemplate.executeWithoutResult(_ -> insertGenre(sourceId)));

    assertThat(write)
        .failsWithin(THREAD_TIMEOUT)
        .withThrowableThat()
        .withStackTraceContaining("names no audit actor");
    assertThat(dsl.fetchExists(GENRE, GENRE.SOURCE_ID.eq(sourceId))).isFalse();
  }

  @Test
  @DisplayName(
      "Should store the signed-in account when a thread the test did not start as the fixture"
          + " actor signs in")
  void shouldStoreTheSignedInAccountWhenAThreadTheTestDidNotStartAsTheFixtureActorSignsIn() {
    var sourceId = "attributed-" + UUID.randomUUID();
    var accountId = UUID.randomUUID();

    try {
      var write =
          runOnNewThread(
              ThreadKind.VIRTUAL,
              () ->
                  runSignedInAs(
                      accountId,
                      () -> transactionTemplate.executeWithoutResult(_ -> insertGenre(sourceId))));

      assertThat(write).succeedsWithin(THREAD_TIMEOUT);
      assertThat(dsl.fetchSingle(GENRE, GENRE.SOURCE_ID.eq(sourceId)).getCreatedBy())
          .isEqualTo(accountId);
    } finally {
      dsl.deleteFrom(GENRE).where(GENRE.SOURCE_ID.eq(sourceId)).execute();
    }
  }

  @Test
  @DisplayName("Should expose the test fixture actor when the test thread begins a transaction")
  void shouldExposeTheTestFixtureActorWhenTheTestThreadBeginsATransaction() {
    assertThat(actorSettingIn(transactionTemplate)).isEqualTo(TEST_FIXTURE_ACTOR_ID.toString());
  }

  @Test
  @DisplayName(
      "Should expose the test fixture actor when a thread the test starts runs as the fixture actor")
  void shouldExposeTheTestFixtureActorWhenAThreadTheTestStartsRunsAsTheFixtureActor() {
    var actorSetting = new AtomicReference<String>();

    var read =
        runOnNewThread(
            ThreadKind.VIRTUAL,
            () ->
                TestFixtureWork.runAsTestFixtureActor(
                    () -> actorSetting.set(actorSettingIn(transactionTemplate))));

    assertThat(read).succeedsWithin(THREAD_TIMEOUT);
    assertThat(actorSetting).hasValue(TEST_FIXTURE_ACTOR_ID.toString());
  }

  @Test
  @Transactional
  @DisplayName(
      "Should expose the test fixture actor when the test framework begins the transaction")
  void shouldExposeTheTestFixtureActorWhenTheTestFrameworkBeginsTheTransaction() {
    assertThat(actorSetting(dsl)).isEqualTo(TEST_FIXTURE_ACTOR_ID.toString());
  }

  enum ThreadKind {
    PLATFORM,
    VIRTUAL;

    private Thread.Builder newBuilder() {
      return switch (this) {
        case PLATFORM -> Thread.ofPlatform();
        case VIRTUAL -> Thread.ofVirtual();
      };
    }
  }

  private static CompletableFuture<Void> runOnNewThread(ThreadKind threadKind, Runnable work) {
    var outcome = new CompletableFuture<Void>();
    threadKind
        .newBuilder()
        .start(
            () -> {
              try {
                work.run();
                outcome.complete(null);
              } catch (RuntimeException failure) {
                outcome.completeExceptionally(failure);
              }
            });
    return outcome;
  }

  private void insertGenre(String sourceId) {
    dsl.insertInto(GENRE).set(GENRE.SOURCE_ID, sourceId).set(GENRE.NAME, "Drama").execute();
  }

  private String actorSettingIn(TransactionTemplate transactions) {
    return transactions.execute(_ -> actorSetting(dsl));
  }

  private static String actorSetting(DSLContext context) {
    return context
        .select(
            DSL.function(
                "current_setting", String.class, DSL.val(AuditActorSetting.NAME), DSL.val(true)))
        .fetchSingle()
        .value1();
  }
}
