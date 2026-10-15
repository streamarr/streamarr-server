package com.streamarr.server.support;

import java.lang.reflect.Method;
import org.junit.jupiter.api.extension.DynamicTestInvocationContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

/**
 * Runs each test's lifecycle and test methods as the test fixture actor on the thread that invokes
 * them. Spring's test callbacks run outside these methods; {@link
 * TestManagedTransactionFixtureActorListener} names the actor of the transaction they begin.
 */
public class TestFixtureActorExtension implements InvocationInterceptor {

  @Override
  public void interceptBeforeAllMethod(
      Invocation<Void> invocation,
      ReflectiveInvocationContext<Method> invocationContext,
      ExtensionContext extensionContext)
      throws Throwable {
    proceedAsTestFixtureActor(invocation);
  }

  @Override
  public void interceptBeforeEachMethod(
      Invocation<Void> invocation,
      ReflectiveInvocationContext<Method> invocationContext,
      ExtensionContext extensionContext)
      throws Throwable {
    proceedAsTestFixtureActor(invocation);
  }

  @Override
  public void interceptTestMethod(
      Invocation<Void> invocation,
      ReflectiveInvocationContext<Method> invocationContext,
      ExtensionContext extensionContext)
      throws Throwable {
    proceedAsTestFixtureActor(invocation);
  }

  @Override
  public <T> T interceptTestFactoryMethod(
      Invocation<T> invocation,
      ReflectiveInvocationContext<Method> invocationContext,
      ExtensionContext extensionContext)
      throws Throwable {
    return proceedAsTestFixtureActor(invocation);
  }

  @Override
  public void interceptTestTemplateMethod(
      Invocation<Void> invocation,
      ReflectiveInvocationContext<Method> invocationContext,
      ExtensionContext extensionContext)
      throws Throwable {
    proceedAsTestFixtureActor(invocation);
  }

  @Override
  public void interceptDynamicTest(
      Invocation<Void> invocation,
      DynamicTestInvocationContext invocationContext,
      ExtensionContext extensionContext)
      throws Throwable {
    proceedAsTestFixtureActor(invocation);
  }

  @Override
  public void interceptAfterEachMethod(
      Invocation<Void> invocation,
      ReflectiveInvocationContext<Method> invocationContext,
      ExtensionContext extensionContext)
      throws Throwable {
    proceedAsTestFixtureActor(invocation);
  }

  @Override
  public void interceptAfterAllMethod(
      Invocation<Void> invocation,
      ReflectiveInvocationContext<Method> invocationContext,
      ExtensionContext extensionContext)
      throws Throwable {
    proceedAsTestFixtureActor(invocation);
  }

  private static <T> T proceedAsTestFixtureActor(Invocation<T> invocation) throws Throwable {
    return TestFixtureWork.callAsTestFixtureActor(invocation::proceed);
  }
}
