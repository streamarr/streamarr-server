package com.streamarr.server.support;

import com.streamarr.server.config.LibraryOperationScheduler;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Runs the library-operation scheduler in every integration test context, as production does. The
 * test profile turns db-scheduler off, which keeps the scheduler from starting with the context;
 * scans and refreshes run only as its executions. A paused context stops the scheduler and a
 * resumed one starts it again. {@link LibraryOperationDriver} runs an operation to its end.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(LibraryOperationDriver.class)
public class RunningLibraryOperationSchedulerConfiguration {

  @Bean
  SmartLifecycle runningLibraryOperationScheduler(LibraryOperationScheduler scheduler) {
    return new SmartLifecycle() {
      private volatile boolean running;

      @Override
      public void start() {
        scheduler.start();
        running = true;
      }

      @Override
      public void stop() {
        scheduler.stop();
        running = false;
      }

      @Override
      public boolean isRunning() {
        return running;
      }
    };
  }
}
