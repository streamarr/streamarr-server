package com.streamarr.server.services.streaming.ffmpeg;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class StderrDrainer implements AutoCloseable {

  static final int DEFAULT_CAPACITY = 200;

  private final CircularLineBuffer buffer;
  private final Thread drainThread;

  StderrDrainer(InputStream errorStream) {
    this(errorStream, DEFAULT_CAPACITY);
  }

  StderrDrainer(InputStream errorStream, int capacity) {
    this.buffer = new CircularLineBuffer(capacity);
    this.drainThread = Thread.ofVirtual().name("stderr-drainer").start(() -> drain(errorStream));
  }

  private void drain(InputStream errorStream) {
    try (var reader =
        new BufferedReader(new InputStreamReader(errorStream, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        buffer.add(line);
      }
    } catch (Exception e) {
      log.debug("Stderr drain thread terminated: {}", e.getMessage());
    }
  }

  List<String> getRecentOutput() {
    return buffer.getLines();
  }

  boolean isDrainThreadAlive() {
    return drainThread.isAlive();
  }

  @Override
  public void close() {
    drainThread.interrupt();
  }
}
