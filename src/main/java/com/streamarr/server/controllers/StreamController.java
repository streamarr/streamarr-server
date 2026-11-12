package com.streamarr.server.controllers;

import com.streamarr.server.domain.streaming.StreamDelivery;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.exceptions.InvalidSegmentPathException;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.streaming.DirectPlayMetrics;
import com.streamarr.server.services.streaming.DirectPlaySource;
import com.streamarr.server.services.streaming.DirectPlaySourceService;
import com.streamarr.server.services.streaming.HlsPlaylistService;
import com.streamarr.server.services.streaming.PlaybackRequest;
import com.streamarr.server.services.streaming.SegmentDelivery;
import com.streamarr.server.services.streaming.SegmentDeliveryCoordinator;
import com.streamarr.server.services.streaming.SegmentNames;
import com.streamarr.server.services.streaming.StoredSegment;
import com.streamarr.server.services.streaming.StreamingService;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

@RestController
@RequestMapping("/api/stream")
@RequiredArgsConstructor
@PlaybackTokenParameter
public class StreamController {

  private static final MediaType HLS_MEDIA_TYPE =
      MediaType.parseMediaType("application/vnd.apple.mpegurl");
  private static final MediaType MP4_MEDIA_TYPE = MediaType.parseMediaType("video/mp4");
  private static final String SOURCE_PATH = "/{sessionId}/source.{extension}";
  private static final String MEDIA_SEGMENT_PATH_VARIABLE =
      "{segmentName:.+\\Q" + SegmentNames.MEDIA_SEGMENT_EXTENSION + "\\E}";

  private final StreamingService streamingService;
  private final HlsPlaylistService playlistService;
  private final SegmentDeliveryCoordinator deliveryCoordinator;
  private final AuthorizationService authorizationService;
  private final DirectPlaySourceService directPlaySources;
  private final DirectPlayMetrics directPlayMetrics;

  @GetMapping("/{sessionId}/multivariant.m3u8")
  @ApiResponse(responseCode = "200")
  @ApiResponse(responseCode = "404", description = "Stream resource not found", content = @Content)
  public ResponseEntity<String> getMultivariantPlaylist(@PathVariable UUID sessionId) {
    var session = findSession(sessionId);
    if (session.isEmpty()) {
      return ResponseEntity.notFound().build();
    }

    var playlist = playlistService.generateMultivariantPlaylist(session.get(), playbackToken());

    return ResponseEntity.ok().contentType(HLS_MEDIA_TYPE).body(playlist);
  }

  @GetMapping("/{sessionId}/stream.m3u8")
  @ApiResponse(responseCode = "200")
  @ApiResponse(responseCode = "404", description = "Stream resource not found", content = @Content)
  public ResponseEntity<String> getMediaPlaylist(@PathVariable UUID sessionId) {
    var session = findSession(sessionId);
    if (session.isEmpty()) {
      return ResponseEntity.notFound().build();
    }

    var playlist =
        playlistService.generateMediaPlaylist(
            session.get(), StreamSession.defaultVariant(), playbackToken());

    return ResponseEntity.ok().contentType(HLS_MEDIA_TYPE).body(playlist);
  }

  @GetMapping("/{sessionId}/" + SegmentNames.INITIALIZATION_SEGMENT_NAME)
  @ApiResponse(
      responseCode = "200",
      content =
          @Content(mediaType = "video/mp4", schema = @Schema(type = "string", format = "binary")))
  @ApiResponse(
      responseCode = "206",
      description = "The requested byte range",
      content =
          @Content(mediaType = "video/mp4", schema = @Schema(type = "string", format = "binary")))
  @ApiResponse(responseCode = "404", description = "Stream resource not found", content = @Content)
  @ApiResponse(responseCode = "503", description = "Segment unavailable", content = @Content)
  public ResponseEntity<Resource> getInitSegment(@PathVariable UUID sessionId) {
    var session = findSession(sessionId);
    if (session.isEmpty()) {
      return ResponseEntity.notFound().build();
    }

    return serveSegment(
        sessionId, StreamSession.defaultVariant(), SegmentNames.INITIALIZATION_SEGMENT_NAME);
  }

  @GetMapping("/{sessionId}/" + MEDIA_SEGMENT_PATH_VARIABLE)
  @ApiResponse(responseCode = "400", description = "Invalid segment path", content = @Content)
  @ApiResponse(
      responseCode = "200",
      content =
          @Content(mediaType = "video/mp4", schema = @Schema(type = "string", format = "binary")))
  @ApiResponse(
      responseCode = "206",
      description = "The requested byte range",
      content =
          @Content(mediaType = "video/mp4", schema = @Schema(type = "string", format = "binary")))
  @ApiResponse(responseCode = "404", description = "Stream resource not found", content = @Content)
  @ApiResponse(responseCode = "503", description = "Segment unavailable", content = @Content)
  public ResponseEntity<Resource> getSegment(
      @PathVariable UUID sessionId, @PathVariable String segmentName) {
    validatePathSegment(segmentName);
    var session = findSession(sessionId);
    if (session.isEmpty()) {
      return ResponseEntity.notFound().build();
    }

    return serveSegment(sessionId, StreamSession.defaultVariant(), segmentName);
  }

  @GetMapping("/{sessionId}/{variantLabel}/stream.m3u8")
  @ApiResponse(responseCode = "200")
  @ApiResponse(responseCode = "404", description = "Stream resource not found", content = @Content)
  @ApiResponse(responseCode = "400", description = "Invalid segment path", content = @Content)
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

    var playlist = playlistService.generateMediaPlaylist(s, variantLabel, playbackToken());

    return ResponseEntity.ok().contentType(HLS_MEDIA_TYPE).body(playlist);
  }

  @GetMapping("/{sessionId}/{variantLabel}/" + SegmentNames.INITIALIZATION_SEGMENT_NAME)
  @ApiResponse(
      responseCode = "200",
      content =
          @Content(mediaType = "video/mp4", schema = @Schema(type = "string", format = "binary")))
  @ApiResponse(
      responseCode = "206",
      description = "The requested byte range",
      content =
          @Content(mediaType = "video/mp4", schema = @Schema(type = "string", format = "binary")))
  @ApiResponse(responseCode = "404", description = "Stream resource not found", content = @Content)
  @ApiResponse(responseCode = "400", description = "Invalid segment path", content = @Content)
  @ApiResponse(responseCode = "503", description = "Segment unavailable", content = @Content)
  public ResponseEntity<Resource> getVariantInitSegment(
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

    return serveSegment(
        sessionId,
        variantLabel,
        SegmentNames.storedSegmentName(variantLabel, SegmentNames.INITIALIZATION_SEGMENT_NAME));
  }

  @GetMapping("/{sessionId}/{variantLabel}/" + MEDIA_SEGMENT_PATH_VARIABLE)
  @ApiResponse(responseCode = "400", description = "Invalid segment path", content = @Content)
  @ApiResponse(responseCode = "503", description = "Segment unavailable", content = @Content)
  @ApiResponse(
      responseCode = "200",
      content =
          @Content(mediaType = "video/mp4", schema = @Schema(type = "string", format = "binary")))
  @ApiResponse(
      responseCode = "206",
      description = "The requested byte range",
      content =
          @Content(mediaType = "video/mp4", schema = @Schema(type = "string", format = "binary")))
  @ApiResponse(responseCode = "404", description = "Stream resource not found", content = @Content)
  public ResponseEntity<Resource> getVariantSegment(
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

    return serveSegment(
        sessionId, variantLabel, SegmentNames.storedSegmentName(variantLabel, segmentName));
  }

  /**
   * Serves the source file of a direct-play stream session. Spring answers a {@code Range} request
   * with 206 and the requested bytes, streamed from disk. Each request passes the live
   * playback-authority check when it starts; a response already streaming is not cut when the
   * authority ends, and the next request is denied.
   */
  @GetMapping(SOURCE_PATH)
  @ApiResponse(
      responseCode = "200",
      content =
          @Content(mediaType = "video/*", schema = @Schema(type = "string", format = "binary")))
  @ApiResponse(
      responseCode = "206",
      description = "The requested byte range",
      content =
          @Content(mediaType = "video/*", schema = @Schema(type = "string", format = "binary")))
  @ApiResponse(responseCode = "404", description = "Stream resource not found", content = @Content)
  @ApiResponse(
      responseCode = "416",
      description = "The requested range lies outside the file",
      content = @Content)
  public ResponseEntity<Resource> getSource(
      @PathVariable UUID sessionId,
      @PathVariable String extension,
      @RequestHeader(name = HttpHeaders.RANGE, required = false) String range) {
    var answer = sourceAnswer(sessionId, extension, range);
    directPlayMetrics.countAnswer(statusSent(answer, range));
    return answer;
  }

  // Spring answers a satisfiable range with 206 when it writes an OK answer's body.
  private static int statusSent(ResponseEntity<Resource> answer, String range) {
    var status = answer.getStatusCode().value();
    if (status == HttpStatus.OK.value() && range != null) {
      return HttpStatus.PARTIAL_CONTENT.value();
    }

    return status;
  }

  private ResponseEntity<Resource> sourceAnswer(UUID sessionId, String extension, String range) {
    var source = openSource(sessionId, extension);
    if (source.isEmpty()) {
      return ResponseEntity.notFound().build();
    }

    var resource = new OpenedSourceResource(source.get());
    // Spring's own 416 still writes the whole file as its body.
    if (range != null && !isSatisfiable(range, resource)) {
      return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
          .header(HttpHeaders.CONTENT_RANGE, "bytes */" + resource.contentLength())
          .build();
    }

    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(source.get().container().getMediaType()))
        .body(resource);
  }

  /**
   * Answers the headers of the direct-play source alone. A range applies only to {@code GET} (RFC
   * 9110 section 14.2), so this ignores one.
   */
  @RequestMapping(value = SOURCE_PATH, method = RequestMethod.HEAD)
  @ApiResponse(responseCode = "200")
  @ApiResponse(responseCode = "404", description = "Stream resource not found", content = @Content)
  public ResponseEntity<Void> getSourceHeaders(
      @PathVariable UUID sessionId, @PathVariable String extension) {
    var answer = sourceHeaders(sessionId, extension);
    directPlayMetrics.countAnswer(answer.getStatusCode().value());
    return answer;
  }

  private ResponseEntity<Void> sourceHeaders(UUID sessionId, String extension) {
    var source = openSource(sessionId, extension);
    if (source.isEmpty()) {
      return ResponseEntity.notFound().build();
    }

    // Spring's implicit HEAD reads the whole file and discards it, so this answers without a body.
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(source.get().container().getMediaType()))
        .contentLength(source.get().length())
        .header(HttpHeaders.ACCEPT_RANGES, "bytes")
        .build();
  }

  private Optional<DirectPlaySource> openSource(UUID sessionId, String extension) {
    requireTokenBoundTo(sessionId);
    var source = directPlaySources.open(sessionId, authorizationService.currentIdentity());
    source.ifPresent(opened -> closeWhenRequestCompletes(DirectPlaySource.class, opened::close));
    return source.filter(opened -> opened.container().getExtension().equals(extension));
  }

  // Spring writes the body after this controller returns, so the request's end closes the file.
  private static void closeWhenRequestCompletes(Class<?> opened, Runnable close) {
    RequestContextHolder.currentRequestAttributes()
        .registerDestructionCallback(opened.getName(), close, RequestAttributes.SCOPE_REQUEST);
  }

  private static boolean isSatisfiable(String range, Resource resource) {
    try {
      HttpRange.toResourceRegions(HttpRange.parseRanges(range), resource);
      return true;
    } catch (IllegalArgumentException _) {
      return false;
    }
  }

  private ResponseEntity<Resource> serveSegment(
      UUID sessionId, String variantLabel, String segmentName) {
    return switch (deliveryCoordinator.deliver(sessionId, variantLabel, segmentName)) {
      case SegmentDelivery.Ready(var segment) -> segmentAnswer(segment);
      case SegmentDelivery.SessionEnded() -> ResponseEntity.notFound().build();
      case SegmentDelivery.Cancelled() ->
          ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
      // Deliberately no Retry-After and no body: the player's own retry/ABR machinery is the
      // designed reaction (ADR 0019).
      case SegmentDelivery.Unrecoverable() ->
          ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    };
  }

  private static ResponseEntity<Resource> segmentAnswer(StoredSegment segment) {
    closeWhenRequestCompletes(StoredSegment.class, segment::close);
    return ResponseEntity.ok().contentType(MP4_MEDIA_TYPE).body(new StoredSegmentResource(segment));
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
      throw new AccessDeniedException("Playback token is not valid for this stream session.");
    }
  }

  private Optional<StreamSession> findSession(UUID sessionId) {
    requireTokenBoundTo(sessionId);
    return streamingService.accessSession(
        PlaybackRequest.builder()
            .streamSessionId(sessionId)
            .identity(authorizationService.currentIdentity())
            .delivery(StreamDelivery.HLS)
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

  /**
   * Serves the file that the request opened. Spring reads one stream for each range, and each
   * stream starts at the first byte of that same file, so no range opens a name again. Spring
   * labels each part of a multi-range answer by the resource's name, so the source takes the name
   * its container gives it, and the file's own name never sets a media type.
   */
  private static final class OpenedSourceResource extends AbstractResource {

    private final DirectPlaySource source;

    private OpenedSourceResource(DirectPlaySource source) {
      this.source = source;
    }

    @Override
    public InputStream getInputStream() {
      return source.newInputStream();
    }

    @Override
    public long contentLength() {
      return source.length();
    }

    @Override
    public String getFilename() {
      return "source." + source.container().getExtension();
    }

    @Override
    public String getDescription() {
      return "direct-play source " + getFilename();
    }

    @Override
    public boolean equals(Object other) {
      return this == other;
    }

    @Override
    public int hashCode() {
      return System.identityHashCode(this);
    }
  }

  /**
   * Serves the segment that the request opened. Spring reads one stream for each range, and each
   * stream starts at the segment's first byte.
   */
  private static final class StoredSegmentResource extends AbstractResource {

    private final StoredSegment segment;

    private StoredSegmentResource(StoredSegment segment) {
      this.segment = segment;
    }

    @Override
    public InputStream getInputStream() {
      return segment.newInputStream();
    }

    @Override
    public long contentLength() {
      return segment.length();
    }

    @Override
    public String getDescription() {
      return "stored segment";
    }

    @Override
    public boolean equals(Object other) {
      return this == other;
    }

    @Override
    public int hashCode() {
      return System.identityHashCode(this);
    }
  }
}
