package com.streamarr.server.support;

import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextPausedEvent;

// Every cached test context owns a pool against the one shared PostgreSQL container, and the
// context cache pauses a context whenever a test switches away from it. Closing the paused
// context's connections keeps the container's connection limit independent of the context count.
@RequiredArgsConstructor
public class PausedContextConnectionRelease implements ApplicationListener<ContextPausedEvent> {

  private final HikariDataSource dataSource;

  @Override
  public void onApplicationEvent(ContextPausedEvent event) {
    dataSource.getHikariPoolMXBean().softEvictConnections();
  }
}
