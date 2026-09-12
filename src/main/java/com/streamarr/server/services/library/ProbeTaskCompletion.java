package com.streamarr.server.services.library;

import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.streamarr.server.domain.task.ProbeInputs;
import com.streamarr.server.domain.task.ProbeRequest;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class ProbeTaskCompletion {

  private final MediaFileContainerInfoRepository outcomes;
  private final PlatformTransactionManager transactionManager;
  private final Clock clock;

  public CompletionHandler<ProbeRequest> handlerFor(
      ProbeRequest request, ProbeExecutionResult result) {
    return (complete, operations) ->
        new TransactionTemplate(transactionManager)
            .executeWithoutResult(
                _ -> {
                  switch (latestResult(request, result)) {
                    case ProbeExecutionResult.Completed _ -> operations.remove();
                    case ProbeExecutionResult.Rescheduled(var next) ->
                        operations.reschedule(complete, clock.instant(), next);
                  }
                });
  }

  private ProbeExecutionResult latestResult(ProbeRequest request, ProbeExecutionResult result) {
    var desired = outcomes.lockProbeInputs(request.mediaFileId());
    if (desired.isEmpty()) {
      return new ProbeExecutionResult.Completed();
    }

    var inputs = desired.get();
    if (!inputs.equals(new ProbeInputs(request.snapshot(), request.probeVersion()))) {
      return new ProbeExecutionResult.Rescheduled(
          request.toBuilder()
              .snapshot(inputs.snapshot())
              .probeVersion(inputs.probeVersion())
              .build());
    }

    if (result instanceof ProbeExecutionResult.Rescheduled(var next)) {
      outcomes.recordProbeRequest(
          next.mediaFileId(), new ProbeInputs(next.snapshot(), next.probeVersion()));
    }

    return result;
  }
}
