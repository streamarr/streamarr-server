package com.streamarr.server.domain.media;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ProbeVersion {

  // Version 3 adds the chosen video stream's keyframe positions. Version 4 adds the dynamic range
  // facts of streamarr/streamarr-server#501 and the other facts that ADR 0032 lists for version 4.
  // Version 5 adds the frame facts below. Version 6 adds the video and audio bitrates that the
  // worker reads from a Matroska file's trusted statistics tags, and whether a copy of an H.264 or
  // HEVC video stream keeps every packet's bytes. Each version includes the facts of every earlier
  // version. Version 2 is unused. V097 deleted every result before version 4; an earlier result
  // stays playable until the re-scan stores the file's current result.
  public static final int CURRENT = 6;

  public static final int FIRST_TO_REPORT_CHAPTERS = 4;

  // The chosen video stream, its frame-rate rule, and each stream's dynamic range, bit depth,
  // chroma subsampling, rotation, container cropping, codec string, sample rate and audio profile.
  public static final int FIRST_TO_REPORT_PLAYBACK_FACTS = 4;

  // Each video stream's codec profile, level, tier and color signaling, and what the probe read
  // in the first frames of the chosen video stream: HDR10+, the Dolby Vision RPUs and their sample
  // layout, and the static HDR metadata that only the bitstream carries.
  public static final int FIRST_TO_REPORT_FRAME_FACTS = 5;

  // Each audio stream's channel layout, sample format, bit depth, six of its dispositions and a
  // FLAC stream's STREAMINFO minimum block size, and the commentary disposition of each audio and
  // subtitle stream.
  public static final int FIRST_TO_REPORT_AUDIO_FACTS = 7;
}
