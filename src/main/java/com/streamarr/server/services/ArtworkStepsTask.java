package com.streamarr.server.services;

import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.streamarr.server.domain.media.ArtworkItem;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.NonNull;

/** The db-scheduler task that runs an item's artwork steps; one instance per item. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ArtworkStepsTask {

  public static Task<ArtworkItem> create(
      @NonNull String name,
      @NonNull ArtworkSteps steps,
      @NonNull ArtworkStepsCompletion completion) {
    return Tasks.custom(name, ArtworkItem.class)
        .onFailure(completion.failureHandler())
        .execute(
            (instance, _) ->
                completion.handlerFor(instance.getData(), steps.runSteps(instance.getData())));
  }

  /** The id of the item's scheduled task instance. */
  public static String instanceId(@NonNull ArtworkItem item) {
    return item.itemType() + ":" + item.itemId();
  }
}
