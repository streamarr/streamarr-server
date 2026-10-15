package com.streamarr.server.services;

import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.streamarr.server.domain.media.ArtworkTarget;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.NonNull;

/** The db-scheduler task that runs a target's artwork steps; one instance per target. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ArtworkStepsTask {

  public static Task<ArtworkTarget> create(
      @NonNull String name,
      @NonNull ArtworkSteps steps,
      @NonNull ArtworkStepsCompletion completion) {
    return Tasks.custom(name, ArtworkTarget.class)
        .onFailure(completion.failureHandler())
        .execute(
            (instance, _) ->
                completion.handlerFor(instance.getData(), steps.runSteps(instance.getData())));
  }

  /** The id of the target's scheduled task instance. */
  public static String instanceId(@NonNull ArtworkTarget target) {
    return target.targetType() + ":" + target.targetId();
  }
}
