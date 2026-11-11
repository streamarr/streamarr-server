package com.streamarr.server.controllers;

import com.streamarr.server.services.streaming.FileBackedStoredSegment;
import com.streamarr.server.services.streaming.StoredSegment;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.apache.catalina.Globals;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Answers a stored segment file through Tomcat's sendfile request attributes, so the kernel copies
 * the file to the socket. Tomcat opens the file by its path after the request ends and writes the
 * body itself, so the answer carries headers and an empty body.
 */
final class TomcatSendfile {

  // Spring flushes an answer's headers only when it opened the answer's body; otherwise Tomcat
  // sends them once the request has ended, after every filter has returned. This empty body makes
  // Spring flush the headers as soon as the controller returns.
  private static final Resource HEADERS_ONLY = new ByteArrayResource(new byte[0]);

  private TomcatSendfile() {}

  /**
   * The answer for the whole segment or for one satisfiable byte range of it. Empty when the
   * connection, the request or the segment leaves the body to Spring: no sendfile support, a method
   * other than {@code GET}, several ranges, or a segment that is not a file.
   */
  static Optional<ResponseEntity<Resource>> answer(
      HttpServletRequest request, StoredSegment segment, MediaType mediaType) {
    if (!(segment instanceof FileBackedStoredSegment file) || !acceptsSendfile(request, file)) {
      return Optional.empty();
    }

    var ranges = HttpRange.parseRanges(request.getHeader(HttpHeaders.RANGE));
    if (ranges.size() > 1) {
      return Optional.empty();
    }

    var length = file.length();
    var first = 0L;
    var last = length - 1;
    var answer = ResponseEntity.ok();
    if (!ranges.isEmpty()) {
      first = ranges.getFirst().getRangeStart(length);
      last = ranges.getFirst().getRangeEnd(length);
      answer =
          ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
              .header(HttpHeaders.CONTENT_RANGE, "bytes %d-%d/%d".formatted(first, last, length));
    }

    request.setAttribute(Globals.SENDFILE_FILENAME_ATTR, file.path().toString());
    request.setAttribute(Globals.SENDFILE_FILE_START_ATTR, first);
    request.setAttribute(Globals.SENDFILE_FILE_END_ATTR, last + 1);
    return Optional.of(
        answer
            .contentType(mediaType)
            .contentLength(last + 1 - first)
            .header(HttpHeaders.ACCEPT_RANGES, "bytes")
            .body(HEADERS_ONLY));
  }

  // Tomcat would send the file after the headers of a HEAD answer, and fails an empty file.
  private static boolean acceptsSendfile(HttpServletRequest request, FileBackedStoredSegment file) {
    return HttpMethod.GET.matches(request.getMethod())
        && file.length() > 0
        && Boolean.TRUE.equals(request.getAttribute(Globals.SENDFILE_SUPPORTED_ATTR));
  }
}
