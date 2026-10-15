package com.streamarr.server.repositories.audit;

import static com.streamarr.server.jooq.generated.tables.Genre.GENRE;
import static com.streamarr.server.support.security.SignedInAccount.callSignedInAs;
import static com.streamarr.server.support.security.SignedInAccount.runSignedInAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.config.persistence.AuditActorTransactionListener;
import com.streamarr.server.config.persistence.SecurityAuditActorSource;
import com.streamarr.server.domain.auth.SystemActor;
import com.streamarr.server.services.audit.SystemWork;
import com.streamarr.server.support.AuditActorSetting;
import com.streamarr.server.support.security.AnonymousRequestAuthentication;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Transaction Audit Actor Binding Integration Tests")
class TransactionAuditActorBindingIT extends AbstractIntegrationTest {

  @Autowired private DSLContext dsl;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private TransactionAuditActorBinder auditActorBinder;
  @Autowired private DataSource dataSource;

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

  @Test
  @DisplayName(
      "Should reject an audited write when a thread with neither an account nor system work"
          + " begins a transaction")
  void shouldRejectAnAuditedWriteWhenAThreadWithNeitherAnAccountNorSystemWorkBeginsATransaction() {
    var sourceId = "unattributed-" + UUID.randomUUID();
    var transactions = transactionsResolvingTheProductionActor();

    assertThatThrownBy(() -> transactions.executeWithoutResult(_ -> insertGenre(sourceId)))
        .hasStackTraceContaining("names no audit actor");

    assertThat(dsl.fetchExists(GENRE, GENRE.SOURCE_ID.eq(sourceId))).isFalse();
  }

  @Test
  @DisplayName("Should store the signed-in account when the production actor source resolves it")
  void shouldStoreTheSignedInAccountWhenTheProductionActorSourceResolvesIt() {
    var sourceId = "attributed-" + UUID.randomUUID();
    var accountId = UUID.randomUUID();
    var transactions = transactionsResolvingTheProductionActor();

    try {
      runSignedInAs(accountId, () -> transactions.executeWithoutResult(_ -> insertGenre(sourceId)));

      assertThat(dsl.fetchSingle(GENRE, GENRE.SOURCE_ID.eq(sourceId)).getCreatedBy())
          .isEqualTo(accountId);
    } finally {
      dsl.deleteFrom(GENRE).where(GENRE.SOURCE_ID.eq(sourceId)).execute();
    }
  }

  // The integration tests' shared context names a test fixture actor on threads that have no
  // actor. A separate transaction manager with the production source shows what production does.
  private TransactionTemplate transactionsResolvingTheProductionActor() {
    var manager = new JdbcTransactionManager(dataSource);
    manager.addListener(
        new AuditActorTransactionListener(
            new SecurityAuditActorSource(), auditActorBinder, new SimpleMeterRegistry()));
    return new TransactionTemplate(manager);
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
