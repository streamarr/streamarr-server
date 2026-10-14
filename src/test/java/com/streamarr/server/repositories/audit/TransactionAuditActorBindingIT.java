package com.streamarr.server.repositories.audit;

import static com.streamarr.server.support.security.SignedInAccount.callSignedInAs;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.SystemActor;
import com.streamarr.server.services.audit.SystemWork;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Transaction Audit Actor Binding Integration Tests")
class TransactionAuditActorBindingIT extends AbstractIntegrationTest {

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
    SecurityContextHolder.getContext()
        .setAuthentication(
            new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

    assertThat(actorSettingIn(transactionTemplate)).isEqualTo("request-without-account");
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

  private String actorSettingIn(TransactionTemplate transactions) {
    return transactions.execute(_ -> actorSetting(dsl));
  }

  private static String actorSetting(DSLContext context) {
    return context
        .select(
            DSL.function(
                "current_setting", String.class, DSL.val("streamarr.actor_id"), DSL.val(true)))
        .fetchSingle()
        .value1();
  }
}
