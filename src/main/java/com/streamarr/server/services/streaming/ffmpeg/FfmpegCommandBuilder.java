package com.streamarr.server.services.streaming.ffmpeg;

import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.AudioMode;
import com.streamarr.server.domain.streaming.SubtitleDecision;
import com.streamarr.server.domain.streaming.SubtitleMode;
import com.streamarr.server.domain.streaming.TranscodeJob;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class FfmpegCommandBuilder {

  private final String ffmpegPath;

  private static final Set<String> GOP_ONLY_ENCODERS =
      Set.of(
          "libsvtav1",
          "h264_nvenc",
          "hevc_nvenc",
          "av1_nvenc",
          "h264_qsv",
          "hevc_qsv",
          "av1_qsv",
          "h264_amf",
          "hevc_amf",
          "av1_amf",
          "h264_rkmpp",
          "hevc_rkmpp");

  private static final Set<String> FORCE_KEYFRAME_ENCODERS =
      Set.of("libx264", "libx265", "h264_vaapi", "hevc_vaapi", "av1_vaapi");

  public List<String> buildCommand(TranscodeJob job) {
    var cmd = new ArrayList<String>();
    var decision = job.request().transcodeDecision();
    var mode = decision.transcodeMode();

    addInputArgs(cmd, job.request());
    addStreamSelection(cmd, decision.audioDecision(), decision.subtitleDecision());
    addCommonFlags(cmd);
    addCodecArgs(cmd, job);

    if (mode == TranscodeMode.VIDEO_TRANSCODE || mode == TranscodeMode.FULL_TRANSCODE) {
      addKeyframeArgs(cmd, job);
    }

    addHlsArgs(cmd, job);

    cmd.add(job.outputDir().resolve("stream.m3u8").toString());

    return List.copyOf(cmd);
  }

  private void addInputArgs(List<String> cmd, TranscodeRequest request) {
    cmd.add(ffmpegPath);
    cmd.add("-y");

    if (request.seekPosition() > 0) {
      cmd.addAll(List.of("-ss", String.valueOf(request.seekPosition())));
    }

    cmd.addAll(List.of("-i", request.sourcePath().toString()));
  }

  private void addStreamSelection(
      List<String> cmd, AudioDecision audio, SubtitleDecision subtitle) {
    cmd.addAll(List.of("-map", "0:v:0"));
    if (audio.mode() != AudioMode.NONE) {
      cmd.addAll(List.of("-map", "0:a:0"));
    }
    if (subtitle.mode() == SubtitleMode.EXCLUDE) {
      cmd.addAll(List.of("-map", "-0:s"));
    }
  }

  private void addCommonFlags(List<String> cmd) {
    cmd.addAll(
        List.of(
            "-map_metadata",
            "-1",
            "-map_chapters",
            "-1",
            "-copyts",
            "-avoid_negative_ts",
            "disabled",
            "-max_muxing_queue_size",
            "128",
            "-max_delay",
            "5000000"));
  }

  private void addCodecArgs(List<String> cmd, TranscodeJob job) {
    var decision = job.request().transcodeDecision();
    var mode = decision.transcodeMode();

    if (mode == TranscodeMode.REMUX || mode == TranscodeMode.AUDIO_TRANSCODE) {
      cmd.addAll(List.of("-c:v", "copy"));
      addAudioArgs(cmd, decision.audioDecision());
      return;
    }

    cmd.addAll(List.of("-c:v", job.videoEncoder()));
    addScaleAndBitrateArgs(cmd, job.request());
    addAudioArgs(cmd, decision.audioDecision());
  }

  private void addAudioArgs(List<String> cmd, AudioDecision audio) {
    if (audio.mode() == AudioMode.NONE) {
      return;
    }
    if (audio.mode() == AudioMode.COPY) {
      cmd.addAll(List.of("-c:a", "copy"));
      return;
    }

    cmd.addAll(List.of("-c:a", audio.codec()));
    cmd.addAll(List.of("-ac", String.valueOf(audio.channels())));
    cmd.addAll(List.of("-b:a", audio.bitrate() / 1000 + "k"));
  }

  private void addScaleAndBitrateArgs(List<String> cmd, TranscodeRequest request) {
    cmd.addAll(List.of("-vf", "scale=-2:" + request.height()));
    var bitrate = String.valueOf(request.bitrate());
    cmd.addAll(
        List.of(
            "-b:v", bitrate,
            "-maxrate", bitrate,
            "-bufsize", String.valueOf(request.bitrate() * 2)));
  }

  private void addKeyframeArgs(List<String> cmd, TranscodeJob job) {
    var encoder = job.videoEncoder();

    cmd.addAll(List.of("-forced-idr", "1"));

    if (GOP_ONLY_ENCODERS.contains(encoder)) {
      addGopSizeArgs(cmd, job);
      return;
    }

    if (FORCE_KEYFRAME_ENCODERS.contains(encoder)) {
      addForceKeyframeExprArgs(cmd, job);
    }
  }

  private void addGopSizeArgs(List<String> cmd, TranscodeJob job) {
    var gopSize = (int) Math.ceil(job.request().segmentDuration() * job.request().framerate());
    cmd.addAll(
        List.of(
            "-g:v:0", String.valueOf(gopSize),
            "-keyint_min:v:0", String.valueOf(gopSize)));
  }

  private void addForceKeyframeExprArgs(List<String> cmd, TranscodeJob job) {
    cmd.addAll(
        List.of(
            "-force_key_frames:0", "expr:gte(t,n_forced*" + job.request().segmentDuration() + ")"));

    if ("libx264".equals(job.videoEncoder())) {
      cmd.addAll(List.of("-sc_threshold:v:0", "0"));
    }
  }

  private void addHlsArgs(List<String> cmd, TranscodeJob job) {
    var request = job.request();
    var decision = request.transcodeDecision();
    var container = decision.containerFormat();
    var extension = container.segmentExtension();

    cmd.addAll(List.of("-f", "hls"));
    cmd.addAll(List.of("-hls_time", String.valueOf(request.segmentDuration())));
    cmd.addAll(List.of("-hls_list_size", "0"));
    cmd.addAll(List.of("-hls_flags", "temp_file"));

    if (request.startNumber() > 0) {
      cmd.addAll(List.of("-start_number", String.valueOf(request.startNumber())));
    }

    switch (container) {
      case FMP4 -> {
        cmd.addAll(List.of("-hls_segment_type", "fmp4"));
        cmd.addAll(List.of("-hls_fmp4_init_filename", "init.mp4"));
        cmd.addAll(List.of("-hls_segment_options", "movflags=+frag_discont"));
      }
      case MPEGTS -> cmd.addAll(List.of("-hls_segment_type", "mpegts"));
    }

    cmd.addAll(
        List.of(
            "-hls_segment_filename", job.outputDir().resolve("segment%d" + extension).toString()));
  }
}
