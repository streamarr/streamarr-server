package com.streamarr.server.controllers;

import com.streamarr.server.services.ImageService;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

@Slf4j
@RestController
@RequestMapping("/api/images")
@RequiredArgsConstructor
public class ImageController {

  /**
   * Bytes never change under a given image id: each file path includes the row id, losing ON
   * CONFLICT inserts clean up only their own files, and rows are only ever deleted together with
   * their file. So a stored copy stays correct indefinitely. Private, because images are
   * profile-scoped media behind SCOPE_PROFILE and must never settle in a shared proxy cache.
   */
  private static final CacheControl PRIVATE_IMMUTABLE =
      CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable();

  private final ImageService imageService;

  @GetMapping("/{imageId}")
  public ResponseEntity<byte[]> getImage(@PathVariable UUID imageId, WebRequest request) {
    var imageOpt = imageService.findById(imageId);

    if (imageOpt.isEmpty()) {
      return ResponseEntity.notFound().build();
    }

    if (request.checkNotModified(imageId.toString())) {
      return ResponseEntity.status(HttpStatus.NOT_MODIFIED).cacheControl(PRIVATE_IMMUTABLE).build();
    }

    try {
      var imageData = imageService.readImageFile(imageOpt.get());

      return ResponseEntity.ok()
          .contentType(MediaType.IMAGE_JPEG)
          .cacheControl(PRIVATE_IMMUTABLE)
          .eTag(imageId.toString())
          .body(imageData);
    } catch (IOException e) {
      log.error("Failed to read image file for id: {}", imageId, e);
      return ResponseEntity.internalServerError().build();
    }
  }
}
