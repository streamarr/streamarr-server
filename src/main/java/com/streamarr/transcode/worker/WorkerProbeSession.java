package com.streamarr.transcode.worker;

import static com.streamarr.transcode.protocol.ProtoUuid.fromProto;

import com.streamarr.transcode.probe.FfprobeExecutor;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeFailure;
import com.streamarr.transcode.v1.ProbeRequest;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class WorkerProbeSession implements AutoCloseable {

  private final Optional<FfprobeExecutor> ffprobe;
  private final WorkerMediaSourceResolver sources;
  private final Consumer<ProbeAttemptResult> results;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final Map<UUID, ProbeAttempt> attempts = new HashMap<>();

  synchronized void start(ProbeRequest request) {
    if (executor.isShutdown()) {
      return;
    }

    var attempt = new ProbeAttempt(request);
    attempts.put(fromProto(request.getProbeAttemptId()), attempt);
    executor.execute(attempt);
  }

  synchronized void cancel(UUID attemptId) {
    var attempt = attempts.get(attemptId);
    if (attempt != null) {
      attempt.cancel();
    }
  }

  synchronized void shutdown() {
    attempts.values().forEach(ProbeAttempt::cancel);
    executor.shutdownNow();
  }

  @Override
  public void close() {
    shutdown();
    executor.close();
  }

  @RequiredArgsConstructor
  private final class ProbeAttempt implements Runnable {

    private final ProbeRequest request;
    private volatile Thread thread;
    private volatile boolean cancelled;

    private void cancel() {
      cancelled = true;
      var runningThread = thread;
      if (runningThread != null) {
        runningThread.interrupt();
      }
    }

    @Override
    public void run() {
      thread = Thread.currentThread();
      if (cancelled) {
        thread.interrupt();
      }

      complete(execute());
    }

    private ProbeAttemptResult execute() {
      if (ffprobe.isEmpty()) {
        return failed(ProbeFailure.PROBE_FAILURE_UNSUPPORTED_VERSION);
      }

      try {
        return ffprobe.orElseThrow().probe(sources.resolve(request.getSource()), request);
      } catch (WorkerJobException _) {
        return failed(ProbeFailure.PROBE_FAILURE_SOURCE_UNAVAILABLE);
      }
    }

    private ProbeAttemptResult failed(ProbeFailure failure) {
      return ProbeAttemptResult.newBuilder()
          .setProbeAttemptId(request.getProbeAttemptId())
          .setProbeVersion(request.getProbeVersion())
          .setFailure(failure)
          .build();
    }

    private void complete(ProbeAttemptResult result) {
      synchronized (WorkerProbeSession.this) {
        attempts.remove(fromProto(result.getProbeAttemptId()), this);
      }

      results.accept(result);
    }
  }
}
