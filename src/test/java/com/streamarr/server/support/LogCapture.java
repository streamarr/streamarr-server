package com.streamarr.server.support;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

/** Captures the Logback events one class emits for the duration of a try-with-resources block. */
public record LogCapture(Logger logger, ListAppender<ILoggingEvent> appender)
    implements AutoCloseable {

  public static LogCapture forClass(Class<?> type) {
    var logger = (Logger) LoggerFactory.getLogger(type);
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    return new LogCapture(logger, appender);
  }

  public List<ILoggingEvent> events() {
    return appender.list;
  }

  @Override
  public void close() {
    logger.detachAppender(appender);
    appender.stop();
  }
}
