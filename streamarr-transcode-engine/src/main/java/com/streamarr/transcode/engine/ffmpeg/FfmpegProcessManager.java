package com.streamarr.transcode.engine.ffmpeg;

import com.streamarr.transcode.engine.model.TranscodeJobRef;
import java.nio.file.Path;
import java.util.List;

public interface FfmpegProcessManager {

  Process startProcess(FfmpegProcessKey key, List<String> command, Path workingDir);

  void stopProcess(FfmpegProcessKey key);

  void stopJob(TranscodeJobRef jobRef);

  void forceStopAll();

  boolean isRunning(TranscodeJobRef jobRef);

  boolean isRunning(FfmpegProcessKey key);

  FfmpegProcessObservation observe(FfmpegProcessKey key);
}
