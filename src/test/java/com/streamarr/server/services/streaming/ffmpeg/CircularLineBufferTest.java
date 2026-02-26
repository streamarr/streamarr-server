package com.streamarr.server.services.streaming.ffmpeg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
class CircularLineBufferTest {

  @Test
  @DisplayName("Should return empty list when no lines have been added")
  void shouldReturnEmptyListWhenNoLinesHaveBeenAdded() {
    var buffer = new CircularLineBuffer(10);

    assertThat(buffer.getLines()).isEmpty();
  }

  @Test
  @DisplayName("Should return all lines when buffer is partially filled")
  void shouldReturnAllLinesWhenBufferIsPartiallyFilled() {
    var buffer = new CircularLineBuffer(5);

    buffer.add("line1");
    buffer.add("line2");
    buffer.add("line3");

    assertThat(buffer.getLines()).containsExactly("line1", "line2", "line3");
  }

  @Test
  @DisplayName("Should return all lines when buffer is exactly at capacity")
  void shouldReturnAllLinesWhenBufferIsExactlyAtCapacity() {
    var buffer = new CircularLineBuffer(3);

    buffer.add("line1");
    buffer.add("line2");
    buffer.add("line3");

    assertThat(buffer.getLines()).containsExactly("line1", "line2", "line3");
  }

  @Test
  @DisplayName("Should drop oldest lines when buffer overflows")
  void shouldDropOldestLinesWhenBufferOverflows() {
    var buffer = new CircularLineBuffer(3);

    buffer.add("line1");
    buffer.add("line2");
    buffer.add("line3");
    buffer.add("line4");
    buffer.add("line5");

    assertThat(buffer.getLines()).containsExactly("line3", "line4", "line5");
  }

  @Test
  @DisplayName("Should handle capacity of one")
  void shouldHandleCapacityOfOne() {
    var buffer = new CircularLineBuffer(1);

    buffer.add("first");
    buffer.add("second");
    buffer.add("third");

    assertThat(buffer.getLines()).containsExactly("third");
  }

  @Test
  @DisplayName("Should return defensive copy from getLines")
  void shouldReturnDefensiveCopyFromGetLines() {
    var buffer = new CircularLineBuffer(5);
    buffer.add("line1");

    List<String> snapshot = buffer.getLines();
    buffer.add("line2");

    assertThat(snapshot).containsExactly("line1");
    assertThat(buffer.getLines()).containsExactly("line1", "line2");
  }

  @Test
  @DisplayName("Should throw when capacity is zero or negative")
  void shouldThrowWhenCapacityIsZeroOrNegative() {
    assertThatThrownBy(() -> new CircularLineBuffer(0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CircularLineBuffer(-1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
