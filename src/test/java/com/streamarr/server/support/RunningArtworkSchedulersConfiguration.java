package com.streamarr.server.support;

import com.streamarr.server.config.ArtworkSchedulers;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;

/**
 * Runs the artwork schedulers in every integration test context, as production does. The test
 * profile turns db-scheduler off, which keeps the artwork schedulers from starting with the
 * context; scans and refreshes wait for recorded artwork results, so they need running schedulers.
 * A paused context stops the schedulers and a resumed one starts them again.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RunningArtworkSchedulersConfiguration {

  @Bean
  SmartLifecycle runningArtworkSchedulers(ArtworkSchedulers artworkSchedulers) {
    return new SmartLifecycle() {
      private volatile boolean running;

      @Override
      public void start() {
        artworkSchedulers.start();
        running = true;
      }

      @Override
      public void stop() {
        artworkSchedulers.stop();
        running = false;
      }

      @Override
      public boolean isRunning() {
        return running;
      }
    };
  }
}
