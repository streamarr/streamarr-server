package com.streamarr.transcode.worker.support;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import lombok.Builder;

public final class ControlledProbeProcess extends Process {

  private final String stdout;
  private final int exitCode;
  private final boolean deferredTermination;
  private final CompletableFuture<Void> started = new CompletableFuture<>();
  private final CompletableFuture<Void> terminationRequested = new CompletableFuture<>();
  private final CompletableFuture<Process> terminated = new CompletableFuture<>();

  @Builder
  private ControlledProbeProcess(
      String stdout, int exitCode, boolean running, boolean deferredTermination) {
    this.stdout = stdout;
    this.exitCode = exitCode;
    this.deferredTermination = deferredTermination;
    if (!running) {
      finish();
    }
  }

  public void awaitStarted() throws Exception {
    started.get(5, TimeUnit.SECONDS);
  }

  public void awaitTerminationRequested() throws Exception {
    terminationRequested.get(5, TimeUnit.SECONDS);
  }

  public void finish() {
    terminated.complete(this);
  }

  @Override
  public OutputStream getOutputStream() {
    return OutputStream.nullOutputStream();
  }

  @Override
  public InputStream getInputStream() {
    return new ByteArrayInputStream(stdout.getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public InputStream getErrorStream() {
    return InputStream.nullInputStream();
  }

  @Override
  public int waitFor() throws InterruptedException {
    started.complete(null);
    try {
      terminated.get();
      if (Thread.interrupted()) {
        throw new InterruptedException("Probe wait interrupted");
      }

      return exitCode;
    } catch (ExecutionException exception) {
      throw new AssertionError(exception);
    }
  }

  @Override
  public int exitValue() {
    if (isAlive()) {
      throw new IllegalThreadStateException("Probe is still running");
    }

    return exitCode;
  }

  @Override
  public boolean isAlive() {
    return !terminated.isDone();
  }

  @Override
  public void destroy() {
    terminationRequested.complete(null);
    if (!deferredTermination) {
      finish();
    }
  }

  @Override
  public Process destroyForcibly() {
    destroy();
    return this;
  }

  @Override
  public CompletableFuture<Process> onExit() {
    return terminated;
  }
}
