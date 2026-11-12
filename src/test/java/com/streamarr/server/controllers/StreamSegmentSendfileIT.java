package com.streamarr.server.controllers;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Drives the segment endpoint on a real port with segments stored as files, so each case shows what
 * Tomcat sends when it copies the file itself: status, headers and the exact body bytes.
 */
@Tag("IntegrationTest")
@DisplayName("Stream Segment Sendfile Integration Tests")
class StreamSegmentSendfileIT extends AbstractStoredSegmentIntegrationTest {

  @Test
  @DisplayName("Should answer the whole segment with its exact bytes when no range is requested")
  void shouldAnswerWholeSegmentWithItsExactBytesWhenNoRangeIsRequested() throws Exception {
    var answer = send(Request.builder().build());

    assertThat(answer.statusLine()).isEqualTo("HTTP/1.1 200 ");
    assertThat(answer.header("Content-Type")).hasValue("video/mp4");
    assertThat(answer.header("Content-Length")).hasValue(String.valueOf(SEGMENT_LENGTH));
    assertThat(answer.header("Accept-Ranges")).hasValue("bytes");
    assertThat(answer.body()).isEqualTo(segmentBytes);
  }

  @Test
  @DisplayName("Should keep the security headers when Tomcat sends the segment file")
  void shouldKeepSecurityHeadersWhenTomcatSendsSegmentFile() throws Exception {
    var answer = send(Request.builder().build());

    assertThat(answer.header("X-Content-Type-Options")).hasValue("nosniff");
  }

  @Test
  @DisplayName(
      "Should answer the bounded range with 206 and its exact bytes when a range is closed")
  void shouldAnswerBoundedRangeWith206AndItsExactBytesWhenRangeIsClosed() throws Exception {
    var answer = send(Request.builder().range("bytes=100000-2099999").build());

    assertThat(answer.statusLine()).isEqualTo("HTTP/1.1 206 ");
    assertThat(answer.header("Content-Range")).hasValue("bytes 100000-2099999/" + SEGMENT_LENGTH);
    assertThat(answer.header("Content-Length")).hasValue("2000000");
    assertThat(answer.body()).isEqualTo(Arrays.copyOfRange(segmentBytes, 100000, 2100000));
  }

  @Test
  @DisplayName("Should answer the final bytes when a range is a suffix")
  void shouldAnswerFinalBytesWhenRangeIsSuffix() throws Exception {
    var answer = send(Request.builder().range("bytes=-10").build());

    assertThat(answer.statusLine()).isEqualTo("HTTP/1.1 206 ");
    assertThat(answer.header("Content-Range"))
        .hasValue(
            "bytes %d-%d/%d".formatted(SEGMENT_LENGTH - 10, SEGMENT_LENGTH - 1, SEGMENT_LENGTH));
    assertThat(answer.body())
        .isEqualTo(Arrays.copyOfRange(segmentBytes, SEGMENT_LENGTH - 10, SEGMENT_LENGTH));
  }

  @Test
  @DisplayName("Should answer 416 without a body when a range starts past the segment's end")
  void shouldAnswer416WithoutBodyWhenRangeStartsPastSegmentEnd() throws Exception {
    var answer = send(Request.builder().range("bytes=" + SEGMENT_LENGTH + "-").build());

    assertThat(answer.statusLine()).isEqualTo("HTTP/1.1 416 ");
    assertThat(answer.header("Content-Range")).hasValue("bytes */" + SEGMENT_LENGTH);
    assertThat(answer.body()).isEmpty();
  }

  @Test
  @DisplayName("Should answer each part when several ranges are requested")
  void shouldAnswerEachPartWhenSeveralRangesAreRequested() throws Exception {
    var answer = send(Request.builder().range("bytes=0-9,100-109").build());

    assertThat(answer.statusLine()).isEqualTo("HTTP/1.1 206 ");
    assertThat(answer.header("Content-Type"))
        .hasValueSatisfying(type -> assertThat(type).startsWith("multipart/byteranges"));
    var body = new String(answer.body(), StandardCharsets.ISO_8859_1);
    assertThat(body)
        .contains(partOf(0, 9))
        .contains(partOf(100, 109))
        .containsOnlyOnce("Content-Range: bytes 0-9/")
        .containsOnlyOnce("Content-Range: bytes 100-109/");
  }

  // One part of a multipart/byteranges body: its last header, a blank line, its bytes, and the
  // line break before the next boundary.
  private String partOf(int first, int last) {
    var bytes = Arrays.copyOfRange(segmentBytes, first, last + 1);
    return "Content-Range: bytes %d-%d/%d\r\n\r\n%s\r\n--"
        .formatted(first, last, SEGMENT_LENGTH, new String(bytes, StandardCharsets.ISO_8859_1));
  }

  @Test
  @DisplayName("Should answer the headers without a body when the method is HEAD")
  void shouldAnswerHeadersWithoutBodyWhenMethodIsHead() throws Exception {
    var answer = send(Request.builder().method("HEAD").build());

    assertThat(answer.statusLine()).isEqualTo("HTTP/1.1 200 ");
    assertThat(answer.header("Content-Type")).hasValue("video/mp4");
    assertThat(answer.body()).isEmpty();
  }

  // Tomcat opens the file by its path after the request ends. A session deleted in between leaves
  // no file to send, so Tomcat closes the connection after the headers.
  @Test
  @DisplayName(
      "Should close the connection after the headers when the session is deleted before Tomcat opens the segment file")
  void shouldCloseConnectionAfterHeadersWhenSessionIsDeletedBeforeTomcatOpensSegmentFile()
      throws Exception {
    STORE.deleteAfterOpening(session.getSessionId());

    var answer = send(Request.builder().build());

    assertThat(answer.statusLine()).isEqualTo("HTTP/1.1 200 ");
    assertThat(answer.header("Content-Length")).hasValue(String.valueOf(SEGMENT_LENGTH));
    assertThat(answer.body()).isEmpty();
  }

  @Builder
  private record Request(String method, String range) {

    private Request {
      method = Objects.requireNonNullElse(method, "GET");
    }

    private String rangeLine() {
      return Optional.ofNullable(range).map(value -> "Range: " + value + "\r\n").orElse("");
    }
  }

  private record Answer(String head, byte[] body) {

    private String statusLine() {
      return head.lines().findFirst().orElseThrow();
    }

    private Optional<String> header(String name) {
      var prefix = name.toLowerCase() + ": ";
      return head.lines()
          .filter(line -> line.toLowerCase().startsWith(prefix))
          .map(line -> line.substring(prefix.length()))
          .findFirst();
    }
  }

  // Reads to the end of the connection, so a body that follows a bodiless answer would show.
  private Answer send(Request request) throws IOException {
    var text =
        "%s %s HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n%s\r\n"
            .formatted(request.method(), segmentPathAndQuery(), request.rangeLine());
    try (var socket = new Socket("127.0.0.1", port)) {
      socket.getOutputStream().write(text.getBytes(StandardCharsets.US_ASCII));
      socket.getOutputStream().flush();
      var received = socket.getInputStream().readAllBytes();
      var headEnd = new String(received, StandardCharsets.ISO_8859_1).indexOf("\r\n\r\n") + 4;
      return new Answer(
          new String(received, 0, headEnd, StandardCharsets.ISO_8859_1),
          Arrays.copyOfRange(received, headEnd, received.length));
    }
  }
}
