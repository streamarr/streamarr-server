package com.streamarr.transcode.worker;

import com.streamarr.server.services.streaming.ffmpeg.FfmpegCommandBuilder;
import com.streamarr.server.services.streaming.ffmpeg.FfmpegTranscodeEngine;
import com.streamarr.server.services.streaming.ffmpeg.LocalFfmpegProcessManager;
import com.streamarr.server.services.streaming.ffmpeg.TranscodeCapabilityService;

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
      throw new IllegalStateException("FFmpeg is not available to the transcode worker");
    }

    var engine =
        new FfmpegTranscodeEngine(
            new FfmpegCommandBuilder(settings.ffmpegPath()),
            new LocalFfmpegProcessManager(),
            capabilities);
    try (var worker = new TranscodeWorker(settings.workerConfiguration(), engine)) {
      var shutdownHook =
          Thread.ofPlatform().name("transcode-worker-shutdown").unstarted(worker::close);
      Runtime.getRuntime().addShutdownHook(shutdownHook);
      worker.start(settings.controlPlaneHost(), settings.controlPlanePort());
      worker.awaitDisconnection();
    }
  }
}
