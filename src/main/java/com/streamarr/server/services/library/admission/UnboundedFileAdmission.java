package com.streamarr.server.services.library.admission;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Variant A: admits every item as soon as it is pulled, one virtual thread each, then joins in
 * pull order. This is the scan's and refresh's original fan-out.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "poc.admission", havingValue = "unbounded", matchIfMissing = true)
public class UnboundedFileAdmission implements FileAdmission {

  private final AdmissionRuntime runtime;

  @Override
  public String name() {
    return "unbounded";
  }

  /** Pulls every item at once: the walk already runs at its own pace. */
  @Override
  public boolean waitsBetweenPulls() {
    return false;
  }

  @Override
  public <T> List<Throwable> processAll(Workload workload, Stream<T> items, AdmittedTask<T> task)
      throws InterruptedException {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var tasks =
          items
              .map(
                  item -> {
                    var ticket = runtime.admit();
                    return executor.submit(() -> ticket.run(task, item));
                  })
              .toList();
      return AdmissionFutures.awaitInOrder(tasks);
    }
  }
}
