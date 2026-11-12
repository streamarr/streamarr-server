package com.streamarr.server.config;

import com.streamarr.server.services.audit.SystemWork;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import org.springframework.boot.web.server.context.WebServerGracefulShutdownLifecycle;

/**
 * Stops the server's background work in the phase of the web server's graceful shutdown. Each
 * lifecycle in that phase stops on its own thread, so Spring stops the db-schedulers, the scheduled
 * tasks and the web server at once and waits for all of them together, for at most {@code
 * spring.lifecycle.timeout-per-shutdown-phase}.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class BackgroundWorkStop {

  public static final int PHASE = WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE;

  /** Runs {@code stop} on a thread of its own and then the lifecycle processor's callback. */
  public static void onItsOwnThread(
      @NonNull String name, @NonNull Runnable stop, @NonNull Runnable callback) {
    SystemWork.startThreadRunningAsSystemActor(
        name,
        () -> {
          try {
            stop.run();
          } finally {
            callback.run();
          }
        });
  }

  /** Runs every stop at once, each on a thread of its own, and returns once all have returned. */
  public static void together(@NonNull String name, @NonNull List<Runnable> stops) {
    try (var stopping = SystemWork.newExecutorRunningAsSystemActor(name)) {
      stops.forEach(stopping::execute);
    }
  }
}
