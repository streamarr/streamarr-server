package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.ContainerFormat;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeMode;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class TranscodeDecisionService {

  private static final List<String> CODEC_PREFERENCE = List.of("av1", "h264");
  private static final String COMPATIBLE_AUDIO_CODEC = "aac";

  public TranscodeDecision decide(MediaProbe source, StreamingOptions clientOptions) {
    var supportedCodecs = clientOptions.supportedCodecs();
    boolean videoCompatible = supportedCodecs.contains(source.videoCodec());
    boolean audioCompatible = COMPATIBLE_AUDIO_CODEC.equals(source.audioCodec());

    if (videoCompatible) {
      return buildCopyVideoDecision(source.videoCodec(), audioCompatible);
    }

    return buildFullTranscodeDecision(supportedCodecs);
  }

  private TranscodeDecision buildCopyVideoDecision(String videoCodec, boolean audioCompatible) {
    var mode = audioCompatible ? TranscodeMode.REMUX : TranscodeMode.PARTIAL_TRANSCODE;

    return TranscodeDecision.builder()
        .transcodeMode(mode)
        .videoCodecFamily(videoCodec)
        .audioCodec(COMPATIBLE_AUDIO_CODEC)
        .containerFormat(containerForCodec(videoCodec))
        .needsKeyframeAlignment(true)
        .build();
  }

  private TranscodeDecision buildFullTranscodeDecision(List<String> supportedCodecs) {
    var selectedCodec = selectPreferredCodec(supportedCodecs);

    return TranscodeDecision.builder()
        .transcodeMode(TranscodeMode.FULL_TRANSCODE)
        .videoCodecFamily(selectedCodec)
        .audioCodec(COMPATIBLE_AUDIO_CODEC)
        .containerFormat(containerForCodec(selectedCodec))
        .needsKeyframeAlignment(false)
        .build();
  }

  private String selectPreferredCodec(List<String> supportedCodecs) {
    for (var codec : CODEC_PREFERENCE) {
      if (supportedCodecs.contains(codec)) {
        return codec;
      }
    }
    return "h264";
  }

  private ContainerFormat containerForCodec(String codecFamily) {
    return switch (codecFamily) {
      case "av1", "hevc" -> ContainerFormat.FMP4;
      default -> ContainerFormat.MPEGTS;
    };
  }
}
