package com.streamarr.server.controllers;

import com.streamarr.server.domain.streaming.ContainerFormat;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.exceptions.InvalidSegmentPathException;
import com.streamarr.server.exceptions.TranscodeException;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.streaming.HlsPlaylistService;
import com.streamarr.server.services.streaming.PlaybackRequest;
import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.server.services.streaming.StreamingService;
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

  private final StreamingService streamingService;
  private final HlsPlaylistService playlistService;
  private final SegmentStore segmentStore;
  private final AuthorizationService authorizationService;

  // RFC 8216bis renamed Master Playlist to Multivariant Playlist; the master.m3u8 route stays
  // as an alias for players holding previously minted URLs.
  @GetMapping({"/{sessionId}/multivariant.m3u8", "/{sessionId}/master.m3u8"})
  public ResponseEntity<String> getMultivariantPlaylist(@PathVariable UUID sessionId) {
    var session = findSession(sessionId);
    if (session.isEmpty()) {
      return ResponseEntity.notFound().build();
    }

    var playlist = playlistService.generateMultivariantPlaylist(session.get(), playbackToken());

    return ResponseEntity.ok().contentType(HLS_MEDIA_TYPE).body(playlist);
  }

  @GetMapping("/{sessionId}/stream.m3u8")
  public ResponseEntity<String> getMediaPlaylist(@PathVariable UUID sessionId) {
    var session = findSession(sessionId);
    if (session.isEmpty()) {
      return ResponseEntity.notFound().build();
    }

    var playlist = playlistService.generateMediaPlaylist(session.get(), playbackToken());

    return ResponseEntity.ok().contentType(HLS_MEDIA_TYPE).body(playlist);
  }

  @GetMapping("/{sessionId}/init.mp4")
  public ResponseEntity<byte[]> getInitSegment(@PathVariable UUID sessionId) {
    var session = findSession(sessionId);
    if (session.isEmpty()) {
      return ResponseEntity.notFound().build();
    }

    return serveInitSegment(session.get(), sessionId, StreamSession.defaultVariant(), "init.mp4");
  }

  @GetMapping("/{sessionId}/{segmentName:.+\\.(?:ts|m4s)}")
  public ResponseEntity<byte[]> getSegment(
      @PathVariable UUID sessionId, @PathVariable String segmentName) {
    validatePathSegment(segmentName);
    var session = findSession(sessionId);
    if (session.isEmpty()) {
      return ResponseEntity.notFound().build();
    }

    return serveSegment(session.get(), sessionId, StreamSession.defaultVariant(), segmentName);
  }

  @GetMapping("/{sessionId}/{variantLabel}/stream.m3u8")
  public ResponseEntity<String> getVariantMediaPlaylist(
      @PathVariable UUID sessionId, @PathVariable String variantLabel) {
    validatePathSegment(variantLabel);
    var session = findSession(sessionId);
    if (session.isEmpty()) {
      return ResponseEntity.notFound().build();
    }

    var s = session.get();
    if (hasNoVariant(s, variantLabel)) {
      return ResponseEntity.notFound().build();
    }

    var playlist = playlistService.generateMediaPlaylist(s, playbackToken());

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
    if (hasNoVariant(s, variantLabel)) {
      return ResponseEntity.notFound().build();
    }

    return serveInitSegment(s, sessionId, variantLabel, variantLabel + "/init.mp4");
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
    if (hasNoVariant(s, variantLabel)) {
      return ResponseEntity.notFound().build();
    }

    var qualifiedName = variantLabel + "/" + segmentName;

    return serveSegment(s, sessionId, variantLabel, qualifiedName);
  }

  private ResponseEntity<byte[]> serveInitSegment(
      StreamSession session, UUID sessionId, String variantLabel, String segmentName) {
    if (session.getTranscodeDecision().containerFormat() != ContainerFormat.FMP4) {
      return ResponseEntity.notFound().build();
    }

    streamingService.resumeSessionIfNeeded(sessionId, segmentName);
    if (!segmentStore.waitForSegment(
        sessionId,
        segmentName,
        () -> streamingService.isTranscodeActive(sessionId, variantLabel))) {
      return ResponseEntity.notFound().build();
    }

    var data = segmentStore.readSegment(sessionId, segmentName);

    return ResponseEntity.ok().contentType(MP4_MEDIA_TYPE).body(data);
  }

  private ResponseEntity<byte[]> serveSegment(
      StreamSession session, UUID sessionId, String variantLabel, String segmentName) {
    streamingService.resumeSessionIfNeeded(sessionId, segmentName);
    if (!segmentStore.waitForSegment(
        sessionId,
        segmentName,
        () -> streamingService.isTranscodeActive(sessionId, variantLabel))) {
      return ResponseEntity.notFound().build();
    }

    var data = segmentStore.readSegment(sessionId, segmentName);
    var contentType = segmentName.endsWith(".ts") ? MPEGTS_MEDIA_TYPE : MP4_MEDIA_TYPE;

    return ResponseEntity.ok().contentType(contentType).body(data);
  }

  private boolean hasNoVariant(StreamSession session, String variantLabel) {
    return session.getVariants().stream().noneMatch(v -> v.label().equals(variantLabel));
  }

  /**
   * The token echoed into playlist segment URLs is the signature-verified one from the security
   * context, never the raw {@code ?t=} parameter — the value written into the response is always
   * server-validated, and the media player carries the same token it authenticated with.
   */
  private String playbackToken() {
    return authorizationService.currentTokenValue();
  }

  /** A playback token is worth exactly the one stream session it was minted for. */
  private void requireTokenBoundTo(UUID sessionId) {
    if (!sessionId.equals(authorizationService.currentIdentity().streamSessionId())) {
      throw new org.springframework.security.access.AccessDeniedException(
          "Playback token is not valid for this stream session.");
    }
  }

  private Optional<StreamSession> findSession(UUID sessionId) {
    requireTokenBoundTo(sessionId);
    return streamingService.accessSession(
        PlaybackRequest.builder()
            .streamSessionId(sessionId)
            .authority(authorizationService.currentIdentity().playbackAuthority())
            .build());
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

  // A segment can vanish between the wait succeeding and the read — a concurrent session destroy
  // wins the race. That is a miss, not a server error, so map it to 404 rather than propagate 500.
  @ExceptionHandler(TranscodeException.class)
  public ResponseEntity<Void> handleMissingSegment() {
    return ResponseEntity.notFound().build();
  }
}
