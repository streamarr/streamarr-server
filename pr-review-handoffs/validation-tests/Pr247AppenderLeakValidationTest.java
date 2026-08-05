package com.streamarr.server.services.streaming.remote;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.LoggerFactory;

/**
 * PR #247 B9 validation. {@code WorkerIdentityServerInterceptorTest} attaches a {@code
 * ListAppender} to the process-global interceptor logger and never detaches it. This ordered pair
 * mirrors that attach and proves a later test's log event is delivered to the leaked appender —
 * the cross-test interference the finding claims. The second method detaches so this validation
 * does not itself pollute the fork.
 */
@Tag("UnitTest")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("PR #247 B9 appender leak validation")
class Pr247AppenderLeakValidationTest {

  private static final ListAppender<ILoggingEvent> leakedAppender = new ListAppender<>();

  @Test
  @Order(1)
  @DisplayName("Should mirror the interceptor test's attach-without-detach")
  void attachWithoutDetachLikeTheInterceptorTest() {
    leakedAppender.start();
    interceptorLogger().addAppender(leakedAppender);
    assertThat(interceptorLogger().isAttached(leakedAppender)).isTrue();
  }

  @Test
  @Order(2)
  @DisplayName("Should deliver a later test's log event to the leaked appender")
  void deliverLaterTestEventToLeakedAppender() {
    try {
      LoggerFactory.getLogger(WorkerIdentityServerInterceptor.class)
          .warn("event emitted by a later test");

      assertThat(leakedAppender.list)
          .extracting(ILoggingEvent::getFormattedMessage)
          .anyMatch(message -> message.contains("event emitted by a later test"));
    } finally {
      interceptorLogger().detachAppender(leakedAppender);
    }
  }

  private static Logger interceptorLogger() {
    return (Logger) LoggerFactory.getLogger(WorkerIdentityServerInterceptor.class);
  }
}
