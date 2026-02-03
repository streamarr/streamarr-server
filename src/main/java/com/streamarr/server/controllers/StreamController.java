package com.streamarr.server.controllers;

import com.streamarr.server.domain.streaming.ContainerFormat;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.services.streaming.HlsPlaylistService;
import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.server.services.streaming.StreamingService;
import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
    if (session == null) {
      return ResponseEntity.notFound().build();
    }

    var playlist = playlistService.generateMasterPlaylist(session);
    return ResponseEntity.ok().contentType(HLS_MEDIA_TYPE).body(playlist);
  }

  @GetMapping("/{sessionId}/stream.m3u8")
  public ResponseEntity<String> getStreamPlaylist(@PathVariable UUID sessionId) {
    var session = findSession(sessionId);
    if (session == null) {
      return ResponseEntity.notFound().build();
    }

    var playlist = playlistService.generateMediaPlaylist(session);
    return ResponseEntity.ok().contentType(HLS_MEDIA_TYPE).body(playlist);
  }

  @GetMapping("/{sessionId}/init.mp4")
  public ResponseEntity<byte[]> getInitSegment(@PathVariable UUID sessionId) {
    var session = findSession(sessionId);
    if (session == null) {
      return ResponseEntity.notFound().build();
    }

    if (session.getTranscodeDecision().containerFormat() != ContainerFormat.FMP4) {
      return ResponseEntity.notFound().build();
    }

    session.getActiveRequestCount().incrementAndGet();
    try {
      if (!segmentStore.waitForSegment(sessionId, "init.mp4", SEGMENT_WAIT_TIMEOUT)) {
        return ResponseEntity.notFound().build();
      }
      var data = segmentStore.readSegment(sessionId, "init.mp4");
      return ResponseEntity.ok().contentType(MP4_MEDIA_TYPE).body(data);
    } finally {
      session.getActiveRequestCount().decrementAndGet();
    }
  }

  @GetMapping("/{sessionId}/{segmentName:.+\\.(?:ts|m4s)}")
  public ResponseEntity<byte[]> getSegment(
      @PathVariable UUID sessionId, @PathVariable String segmentName) {
    var session = findSession(sessionId);
    if (session == null) {
      return ResponseEntity.notFound().build();
    }

    return serveSegment(session, sessionId, segmentName);
  }

  @GetMapping("/{sessionId}/{variantLabel}/stream.m3u8")
  public ResponseEntity<String> getVariantStreamPlaylist(
      @PathVariable UUID sessionId, @PathVariable String variantLabel) {
    var session = findSession(sessionId);
    if (session == null) {
      return ResponseEntity.notFound().build();
    }

    if (!hasVariant(session, variantLabel)) {
      return ResponseEntity.notFound().build();
    }

    var playlist = playlistService.generateMediaPlaylist(session);
    return ResponseEntity.ok().contentType(HLS_MEDIA_TYPE).body(playlist);
  }

  @GetMapping("/{sessionId}/{variantLabel}/init.mp4")
  public ResponseEntity<byte[]> getVariantInitSegment(
      @PathVariable UUID sessionId, @PathVariable String variantLabel) {
    var session = findSession(sessionId);
    if (session == null) {
      return ResponseEntity.notFound().build();
    }

    if (!hasVariant(session, variantLabel)) {
      return ResponseEntity.notFound().build();
    }

    if (session.getTranscodeDecision().containerFormat() != ContainerFormat.FMP4) {
      return ResponseEntity.notFound().build();
    }

    var qualifiedName = variantLabel + "/init.mp4";
    session.getActiveRequestCount().incrementAndGet();
    try {
      if (!segmentStore.waitForSegment(sessionId, qualifiedName, SEGMENT_WAIT_TIMEOUT)) {
        return ResponseEntity.notFound().build();
      }
      var data = segmentStore.readSegment(sessionId, qualifiedName);
      return ResponseEntity.ok().contentType(MP4_MEDIA_TYPE).body(data);
    } finally {
      session.getActiveRequestCount().decrementAndGet();
    }
  }

  @GetMapping("/{sessionId}/{variantLabel}/{segmentName:.+\\.(?:ts|m4s)}")
  public ResponseEntity<byte[]> getVariantSegment(
      @PathVariable UUID sessionId,
      @PathVariable String variantLabel,
      @PathVariable String segmentName) {
    var session = findSession(sessionId);
    if (session == null) {
      return ResponseEntity.notFound().build();
    }

    if (!hasVariant(session, variantLabel)) {
      return ResponseEntity.notFound().build();
    }

    var qualifiedName = variantLabel + "/" + segmentName;
    return serveSegment(session, sessionId, qualifiedName);
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

  private StreamSession findSession(UUID sessionId) {
    return streamingService.getSession(sessionId).orElse(null);
  }
}
