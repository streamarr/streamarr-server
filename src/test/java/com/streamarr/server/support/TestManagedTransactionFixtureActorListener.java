package com.streamarr.server.support;

import com.streamarr.server.config.persistence.AuditActorSource;
import com.streamarr.server.repositories.audit.TransactionAuditActorBinder;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.test.context.transaction.TransactionalTestExecutionListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Names the actor of a test-managed transaction. Spring begins that transaction before the test's
 * own methods run, so it begins outside {@link TestFixtureActorExtension}'s binding and names no
 * actor. This listener runs right after it begins, before the test method writes, and names the
 * actor the test thread would have named while running as the test fixture actor.
 *
 * <p>Spring also calls {@code @BeforeTransaction} and {@code @AfterTransaction} methods and
 * {@code @Sql} scripts outside that binding. Any of them that writes audited rows in its own
 * transaction names no actor and is rejected.
 */
public class TestManagedTransactionFixtureActorListener extends AbstractTestExecutionListener {

  @Override
  public int getOrder() {
    return TransactionalTestExecutionListener.ORDER + 1;
  }

  @Override
  public void beforeTestMethod(TestContext testContext) {
    if (!TestTransaction.isActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      return;
    }

    var applicationContext = testContext.getApplicationContext();
    var actorSource = applicationContext.getBean(AuditActorSource.class);
    var binder = applicationContext.getBean(TransactionAuditActorBinder.class);
    TestFixtureWork.runAsTestFixtureActor(
        () -> actorSource.currentActor().ifPresent(binder::bindToCurrentTransaction));
  }
}
