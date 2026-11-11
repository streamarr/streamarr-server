package com.streamarr.server.support;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

/**
 * Captures the Logback events one class, or every class, emits for the duration of a
 * try-with-resources block.
 */
public record LogCapture(Logger logger, ListAppender<ILoggingEvent> appender)
    implements AutoCloseable {

  public static LogCapture forClass(Class<?> type) {
    return attachedTo((Logger) LoggerFactory.getLogger(type));
  }

  /** Every event any logger passes up to the root, from any thread, at the levels it logs. */
  public static LogCapture everything() {
    return attachedTo((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME));
  }

  private static LogCapture attachedTo(Logger logger) {
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    return new LogCapture(logger, appender);
  }

  public List<ILoggingEvent> events() {
    return appender.list;
  }

  /** Each captured event as the text a log sink receives: its message plus any stack trace. */
  public List<String> renderedEvents() {
    return appender.list.stream().map(LogCapture::render).toList();
  }

  private static String render(ILoggingEvent event) {
    return event.getFormattedMessage()
        + System.lineSeparator()
        + ThrowableProxyUtil.asString(event.getThrowableProxy());
  }

  @Override
  public void close() {
    logger.detachAppender(appender);
    appender.stop();
  }
}
