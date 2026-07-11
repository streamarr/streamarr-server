package com.streamarr.server.services.streaming.ffmpeg;

import java.util.ArrayList;
import java.util.List;

final class CircularLineBuffer {

  private final String[] buffer;
  private int head;
  private int size;

  CircularLineBuffer(int capacity) {
    if (capacity <= 0) {
      throw new IllegalArgumentException("Capacity must be positive, got: " + capacity);
    }
    this.buffer = new String[capacity];
  }

  synchronized void add(String line) {
    buffer[head] = line;
    head = (head + 1) % buffer.length;
    if (size < buffer.length) {
      size++;
    }
  }

  synchronized List<String> getLines() {
    var lines = new ArrayList<String>(size);
    var start = (head - size + buffer.length) % buffer.length;
    for (var i = 0; i < size; i++) {
      lines.add(buffer[(start + i) % buffer.length]);
    }
    return lines;
  }
}
