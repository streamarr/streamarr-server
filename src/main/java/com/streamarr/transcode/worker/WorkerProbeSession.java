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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Builder(access = AccessLevel.PACKAGE)
final class WorkerProbeSession implements AutoCloseable {

  @NonNull private final Optional<FfprobeExecutor> ffprobe;
  @NonNull private final WorkerMediaSourceResolver sources;
  @NonNull private final Consumer<ProbeAttemptResult> results;
  @NonNull private final ExecutorService executor;
  private final Map<UUID, ProbeAttempt> attempts = new HashMap<>();

  synchronized void start(ProbeRequest request) {
    if (executor.isShutdown()) {
      return;
    }

    var attempt = new ProbeAttempt(request);
    var attemptId = fromProto(request.getProbeAttemptId());
    if (attempts.putIfAbsent(attemptId, attempt) != null) {
      log.warn("Ignoring duplicate probe attempt {}", attemptId);
      return;
    }

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
    private final AtomicReference<Thread> thread = new AtomicReference<>();
    private volatile boolean cancelled;

    private void cancel() {
      cancelled = true;
      var runningThread = thread.get();
      if (runningThread != null) {
        runningThread.interrupt();
      }
    }

    @Override
    public void run() {
      var runningThread = Thread.currentThread();
      thread.set(runningThread);
      if (cancelled) {
        runningThread.interrupt();
      }

      complete(execute());
    }

    private ProbeAttemptResult execute() {
      if (ffprobe.isEmpty()) {
        return failed(ProbeFailure.PROBE_FAILURE_UNSUPPORTED_VERSION);
      }

      try {
        return ffprobe.orElseThrow().probe(sources.resolve(request.getSource()), request);
      } catch (WorkerJobException exception) {
        log.warn(
            "Probe {} cannot resolve source namespace {} key {}",
            fromProto(request.getProbeAttemptId()),
            fromProto(request.getSource().getSourceNamespaceId()),
            request.getSource().getRelativeKey(),
            exception);
        return failed(ProbeFailure.PROBE_FAILURE_SOURCE_UNAVAILABLE);
      } catch (RuntimeException exception) {
        log.error(
            "Probe {} failed unexpectedly for source namespace {} key {}",
            fromProto(request.getProbeAttemptId()),
            fromProto(request.getSource().getSourceNamespaceId()),
            request.getSource().getRelativeKey(),
            exception);
        return failed(ProbeFailure.PROBE_FAILURE_EXECUTION_FAILED);
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
