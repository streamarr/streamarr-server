package com.streamarr.server.config;

import com.streamarr.server.domain.media.ArtworkTarget;
import com.streamarr.server.services.ArtworkPriority;
import com.streamarr.server.services.ArtworkScheduling;
import lombok.Builder;
import lombok.NonNull;
import org.springframework.context.SmartLifecycle;

/**
 * Routes artwork steps to the scheduler of their priority. Primary and background artwork run on
 * separate schedulers with separate threads, so a backlog of person photos and company logos never
 * takes the threads of posters and backdrops, and neither takes the threads that run probes. The
 * schedulers start with the application only while db-scheduler is enabled.
 */
@Builder
public class ArtworkPrioritySchedulers implements ArtworkScheduling, SmartLifecycle {

  @NonNull private final ArtworkScheduler primary;
  @NonNull private final ArtworkScheduler background;
  private final boolean autoStartup;

  @Override
  public void scheduleSteps(@NonNull ArtworkTarget target) {
    switch (ArtworkPriority.of(target.targetType())) {
      case PRIMARY -> primary.scheduleSteps(target);
      case BACKGROUND -> background.scheduleSteps(target);
    }
  }

  @Override
  public void start() {
    primary.start();
    background.start();
  }

  @Override
  public void stop() {
    primary.stop();
    background.stop();
  }

  @Override
  public boolean isRunning() {
    return primary.isRunning() || background.isRunning();
  }

  @Override
  public boolean isAutoStartup() {
    return autoStartup;
  }
}
