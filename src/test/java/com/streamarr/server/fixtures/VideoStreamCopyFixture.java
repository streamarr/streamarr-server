package com.streamarr.server.fixtures;

import com.streamarr.server.domain.streaming.CompatibleBase;
import com.streamarr.server.domain.streaming.DolbyVision;
import com.streamarr.server.domain.streaming.Fraction;
import com.streamarr.server.domain.streaming.PictureSize;
import com.streamarr.server.domain.streaming.Presence;
import com.streamarr.server.domain.streaming.ProbedCodecFamily;
import com.streamarr.server.domain.streaming.VideoCodecProfile;
import com.streamarr.server.domain.streaming.VideoCodecTier;
import com.streamarr.server.domain.streaming.VideoDynamicRange;
import com.streamarr.server.domain.streaming.VideoStreamCopy;
import com.streamarr.server.domain.streaming.VideoStreamProperties;
import java.util.Optional;
import java.util.OptionalInt;
import lombok.experimental.UtilityClass;

/** Stream copies as a probe version 5 outcome describes them. */
@UtilityClass
public class VideoStreamCopyFixture {

  /** An SDR HEVC Main 10 copy at Main tier level 4.1, 10 bits, 1920×1080 and 30 fps. */
  public static VideoStreamCopy.VideoStreamCopyBuilder sdrHevcCopyBuilder() {
    return VideoStreamCopy.builder()
        .codecFamily(ProbedCodecFamily.HEVC)
        .videoProperties(
            Optional.of(
                VideoStreamProperties.builder()
                    .codecProfile(Optional.of(VideoCodecProfile.HEVC_MAIN_10))
                    .codecLevel(OptionalInt.of(41))
                    .codecTier(Optional.of(VideoCodecTier.MAIN))
                    .firstFramesRead(true)
                    .build()))
        .bitDepth(OptionalInt.of(10))
        .decodedSize(Optional.of(new PictureSize(1920, 1080)))
        .frameRate(Optional.of(new Fraction(30, 1)))
        .dynamicRange(
            new VideoDynamicRange(CompatibleBase.SDR, Presence.ABSENT, new DolbyVision.Absent()));
  }
}
