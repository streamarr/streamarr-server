package com.streamarr.server.domain.streaming;

/** The order of the fields of an interlaced frame, as ffprobe reports field_order. */
public enum FieldOrder {
  PROGRESSIVE,
  /** Top field coded first and displayed first. */
  TT,
  /** Bottom field coded first and displayed first. */
  BB,
  /** Top field coded first, bottom field displayed first. */
  TB,
  /** Bottom field coded first, top field displayed first. */
  BT
}
