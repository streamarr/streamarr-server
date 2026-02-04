package com.streamarr.server.controllers;

import com.streamarr.server.domain.streaming.ContainerFormat;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.exceptions.InvalidSegmentPathException;
import com.streamarr.server.services.streaming.HlsPlaylistService;
import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.server.services.streaming.StreamingService;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stream")
@RequiredArgsConstructor
public class StreamController {

  private static final MediaType HLS_MEDIA_TYPE =
      MediaType.parseMediaType("application/vnd.apple.mpegurl");
  private static final MediaType MPEGTS_MEDIA_TYPE = MediaType.parseMediaType("video/mp2t");
  private static final MediaType MP4_MEDIA_TYPE = MediaType.parseMediaType("video/mp4");
  private static final Duration SEGMENT_WAIT_TIMEOUT = Duration.ofSeconds(10);

  private final StreamingService streamingService;
  private final HlsPlaylistService playlistService;
  private final SegmentStore segmentStore;

  @GetMapping("/{sessionId}/master.m3u8")
  public ResponseEntity<String> getMasterPlaylist(@PathVariable UUID sessionId) {
    var session = findSession(sessionId);
    if (session.isEmpty()) {
      return ResponseEntity.notFound().build();
    }

    var playlist = playlistService.generateMasterPlaylist(session.get());
    return ResponseEntity.ok().contentType(HLS_MEDIA_TYPE).body(playlist);
  }

  @GetMapping("/{sessionId}/stream.m3u8")
  public ResponseEntity<String> getStreamPlaylist(@PathVariable UUID sessionId) {
    var session = findSession(sessionId);
    if (session.isEmpty()) {
      return ResponseEntity.notFound().build();
    }

    var playlist = playlistService.generateMediaPlaylist(session.get());
    return ResponseEntity.ok().contentType(HLS_MEDIA_TYPE).body(playlist);
  }

  @GetMapping("/{sessionId}/init.mp4")
  public ResponseEntity<byte[]> getInitSegment(@PathVariable UUID sessionId) {
    var session = findSession(sessionId);
    if (session.isEmpty()) {
      return ResponseEntity.notFound().build();
    }

    var s = session.get();
    if (s.getTranscodeDecision().containerFormat() != ContainerFormat.FMP4) {
      return ResponseEntity.notFound().build();
    }

    s.getActiveRequestCount().incrementAndGet();
    try {
      if (!segmentStore.waitForSegment(sessionId, "init.mp4", SEGMENT_WAIT_TIMEOUT)) {
        return ResponseEntity.notFound().build();
      }
      var data = segmentStore.readSegment(sessionId, "init.mp4");
      return ResponseEntity.ok().contentType(MP4_MEDIA_TYPE).body(data);
    } finally {
      s.getActiveRequestCount().decrementAndGet();
    }
  }

  @GetMapping("/{sessionId}/{segmentName:.+\\.(?:ts|m4s)}")
  public ResponseEntity<byte[]> getSegment(
      @PathVariable UUID sessionId, @PathVariable String segmentName) {
    validatePathSegment(segmentName);
    var session = findSession(sessionId);
    if (session.isEmpty()) {
      return ResponseEntity.notFound().build();
    }

    return serveSegment(session.get(), sessionId, segmentName);
  }

  @GetMapping("/{sessionId}/{variantLabel}/stream.m3u8")
  public ResponseEntity<String> getVariantStreamPlaylist(
      @PathVariable UUID sessionId, @PathVariable String variantLabel) {
    validatePathSegment(variantLabel);
    var session = findSession(sessionId);
    if (session.isEmpty()) {
      return ResponseEntity.notFound().build();
    }

    var s = session.get();
    if (!hasVariant(s, variantLabel)) {
      return ResponseEntity.notFound().build();
    }

    var playlist = playlistService.generateMediaPlaylist(s);
    return ResponseEntity.ok().contentType(HLS_MEDIA_TYPE).body(playlist);
  }

  @GetMapping("/{sessionId}/{variantLabel}/init.mp4")
  public ResponseEntity<byte[]> getVariantInitSegment(
      @PathVariable UUID sessionId, @PathVariable String variantLabel) {
    validatePathSegment(variantLabel);
    var session = findSession(sessionId);
    if (session.isEmpty()) {
      return ResponseEntity.notFound().build();
    }

    var s = session.get();
    if (!hasVariant(s, variantLabel)) {
      return ResponseEntity.notFound().build();
    }

    if (s.getTranscodeDecision().containerFormat() != ContainerFormat.FMP4) {
      return ResponseEntity.notFound().build();
    }

    var qualifiedName = variantLabel + "/init.mp4";
    s.getActiveRequestCount().incrementAndGet();
    try {
      if (!segmentStore.waitForSegment(sessionId, qualifiedName, SEGMENT_WAIT_TIMEOUT)) {
        return ResponseEntity.notFound().build();
      }
      var data = segmentStore.readSegment(sessionId, qualifiedName);
      return ResponseEntity.ok().contentType(MP4_MEDIA_TYPE).body(data);
    } finally {
      s.getActiveRequestCount().decrementAndGet();
    }
  }

  @GetMapping("/{sessionId}/{variantLabel}/{segmentName:.+\\.(?:ts|m4s)}")
  public ResponseEntity<byte[]> getVariantSegment(
      @PathVariable UUID sessionId,
      @PathVariable String variantLabel,
      @PathVariable String segmentName) {
    validatePathSegment(variantLabel);
    validatePathSegment(segmentName);
    var session = findSession(sessionId);
    if (session.isEmpty()) {
      return ResponseEntity.notFound().build();
    }

    var s = session.get();
    if (!hasVariant(s, variantLabel)) {
      return ResponseEntity.notFound().build();
    }

    var qualifiedName = variantLabel + "/" + segmentName;
    return serveSegment(s, sessionId, qualifiedName);
  }

  private ResponseEntity<byte[]> serveSegment(
      StreamSession session, UUID sessionId, String segmentName) {
    session.getActiveRequestCount().incrementAndGet();
    try {
      if (!segmentStore.waitForSegment(sessionId, segmentName, SEGMENT_WAIT_TIMEOUT)) {
        return ResponseEntity.notFound().build();
      }

      var data = segmentStore.readSegment(sessionId, segmentName);
      var contentType = segmentName.endsWith(".ts") ? MPEGTS_MEDIA_TYPE : MP4_MEDIA_TYPE;
      return ResponseEntity.ok().contentType(contentType).body(data);
    } finally {
      session.getActiveRequestCount().decrementAndGet();
    }
  }

  private boolean hasVariant(StreamSession session, String variantLabel) {
    return session.getVariants().stream().anyMatch(v -> v.label().equals(variantLabel));
  }

  private Optional<StreamSession> findSession(UUID sessionId) {
    return streamingService.accessSession(sessionId);
  }

  private void validatePathSegment(String segment) {
    if (segment.contains("..") || segment.contains("/") || segment.contains("\\")) {
      throw new InvalidSegmentPathException(segment);
    }
  }

  @ExceptionHandler(InvalidSegmentPathException.class)
  public ResponseEntity<Void> handleInvalidSegmentPath() {
    return ResponseEntity.badRequest().build();
  }
}
