package com.streamarr.transcode.worker;

import com.streamarr.transcode.engine.FfmpegCommandBuilder;
import com.streamarr.transcode.engine.FfmpegTranscodeEngine;
import com.streamarr.transcode.engine.LocalFfmpegProcessManager;
import com.streamarr.transcode.engine.TranscodeCapabilityService;
import com.streamarr.transcode.probe.FfprobeExecutor;
import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.nio.file.Path;

public final class TranscodeWorkerApplication {

  private TranscodeWorkerApplication() {}

  @SuppressWarnings("java:S1172") // The JVM entry-point signature requires the argument.
  public static void main(String[] args) throws Exception {
    var settings = TranscodeWorkerSettings.fromEnvironment(System.getenv());
    var capabilities =
        new TranscodeCapabilityService(
            settings.ffmpegPath(), command -> new ProcessBuilder(command).start());
    capabilities.detectCapabilities();
    if (!capabilities.isFfmpegAvailable()) {
      throw new IllegalStateException(
          "FFmpeg is not available to the transcode worker: "
              + capabilities.getUnavailableReason());
    }

    requireFfprobe(settings.ffprobePath());
    var engine =
        new FfmpegTranscodeEngine(
            new FfmpegCommandBuilder(settings.ffmpegPath()),
            new LocalFfmpegProcessManager(),
            capabilities);
    var ffprobe = FfprobeExecutor.forBinary(Path.of(settings.ffprobePath()));
    try (var worker = new TranscodeWorker(settings.workerConfiguration(), engine, ffprobe)) {
      var shutdownHook =
          Thread.ofPlatform().name("transcode-worker-shutdown").unstarted(worker::close);
      Runtime.getRuntime().addShutdownHook(shutdownHook);
      worker.start(settings.controlPlaneHost(), settings.controlPlanePort());
      worker.awaitDisconnection();
    }
  }

  private static void requireFfprobe(String ffprobePath) throws IOException, InterruptedException {
    Process process;
    try {
      process =
          new ProcessBuilder(ffprobePath, "-version")
              .redirectOutput(Redirect.DISCARD)
              .redirectError(Redirect.DISCARD)
              .start();
    } catch (IOException exception) {
      throw new IOException(
          "ffprobe is not available to the transcode worker: " + ffprobePath, exception);
    }

    try {
      var exitCode = process.waitFor();
      if (exitCode != 0) {
        throw new IllegalStateException(
            "ffprobe is not available to the transcode worker: "
                + ffprobePath
                + " exited with code "
                + exitCode);
      }
    } catch (InterruptedException exception) {
      process.destroyForcibly();
      process.onExit().join();
      Thread.currentThread().interrupt();
      throw exception;
    }
  }
}
