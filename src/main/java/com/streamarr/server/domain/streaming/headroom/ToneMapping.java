package com.streamarr.server.domain.streaming.headroom;

/** The tone-mapping chain of a job attempt, which selects its tone-mapping constant. */
public enum ToneMapping implements CostConstant {
  NONE,
  PQ,
  HLG,
  DOLBY_VISION_RESHAPE
}
