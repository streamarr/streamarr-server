package com.streamarr.server.fixtures;

import com.streamarr.server.services.playbackreport.PlayerFigures;
import com.streamarr.server.services.playbackreport.PlayerFigures.PictureSize;
import com.streamarr.server.services.playbackreport.PlayerFigures.PlayerAudio;
import com.streamarr.server.services.playbackreport.PlayerFigures.PlayerAudioOutput;
import com.streamarr.server.services.playbackreport.PlayerFigures.PlayerBrowser;
import com.streamarr.server.services.playbackreport.PlayerFigures.PlayerDisplay;
import com.streamarr.server.services.playbackreport.PlayerFigures.PlayerScreen;
import com.streamarr.server.services.playbackreport.PlayerFigures.QualityStep;
import com.streamarr.server.services.playbackreport.PlayerHdrMode;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import lombok.experimental.UtilityClass;

@UtilityClass
public class PlayerFiguresFixture {

  /** A player that knew none of its figures yet. */
  public static PlayerFigures.PlayerFiguresBuilder unknownFiguresBuilder() {
    return PlayerFigures.builder()
        .qualityStep(Optional.empty())
        .observedBitrate(OptionalDouble.empty())
        .bufferSeconds(OptionalDouble.empty())
        .droppedFrames(OptionalInt.empty())
        .stalls(OptionalInt.empty())
        .resolution(Optional.empty())
        .videoCodec(Optional.empty())
        .hdrMode(Optional.empty())
        .audio(Optional.empty())
        .display(Optional.empty())
        .audioOutput(Optional.empty())
        .screen(Optional.empty())
        .browser(Optional.empty());
  }

  /** The figures of a TV player that plays a 2160p HDR10 encode with 5.1 E-AC-3 Atmos. */
  public static PlayerFigures.PlayerFiguresBuilder tvFiguresBuilder() {
    return unknownFiguresBuilder()
        .qualityStep(
            Optional.of(
                QualityStep.builder().number(1).count(3).height(2160).bitrate(38_400_000).build()))
        .observedBitrate(OptionalDouble.of(212_000_000))
        .bufferSeconds(OptionalDouble.of(24.5))
        .droppedFrames(OptionalInt.of(0))
        .stalls(OptionalInt.of(1))
        .resolution(Optional.of(new PictureSize(3840, 2160)))
        .videoCodec(Optional.of("hevc"))
        .hdrMode(Optional.of(PlayerHdrMode.HDR10))
        .audio(
            Optional.of(
                PlayerAudio.builder()
                    .codec("eac3")
                    .channels(OptionalInt.of(6))
                    .dolbyAtmos(Optional.of(true))
                    .build()))
        .display(
            Optional.of(
                unknownDisplayBuilder()
                    .width(OptionalInt.of(3840))
                    .height(OptionalInt.of(2160))
                    .hdrMode(Optional.of(PlayerHdrMode.HDR10))
                    .refreshRate(OptionalDouble.of(23.976))
                    .dynamicRangeMatched(Optional.of(true))
                    .frameRateMatched(Optional.of(true))
                    .build()))
        .audioOutput(Optional.of(new PlayerAudioOutput("HDMI", 8)));
  }

  /** A TV display mode whose figures the TV did not report yet, as while it switches modes. */
  public static PlayerDisplay.PlayerDisplayBuilder unknownDisplayBuilder() {
    return PlayerDisplay.builder()
        .width(OptionalInt.empty())
        .height(OptionalInt.empty())
        .hdrMode(Optional.empty())
        .refreshRate(OptionalDouble.empty())
        .dynamicRangeMatched(Optional.empty())
        .frameRateMatched(Optional.empty());
  }

  /** The figures of a web player on a 2560x1440 HDR screen at a pixel ratio of 2. */
  public static PlayerFigures.PlayerFiguresBuilder webFiguresBuilder() {
    return unknownFiguresBuilder()
        .screen(
            Optional.of(
                PlayerScreen.builder().width(2560).height(1440).pixelRatio(2).hdr(true).build()))
        .browser(Optional.of(new PlayerBrowser("Safari", "18.4")));
  }
}
