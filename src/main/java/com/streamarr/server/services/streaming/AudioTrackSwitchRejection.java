package com.streamarr.server.services.streaming;

/** Why a playback session refused the viewer's switch to another audio track. */
public enum AudioTrackSwitchRejection {
  PLAYBACK_SESSION_CLOSED,
  AUDIO_TRACK_NOT_IN_FILE
}
