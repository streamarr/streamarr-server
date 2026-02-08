package com.streamarr.server.controllers;

import com.streamarr.server.services.ImageService;
import java.io.IOException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/images")
@RequiredArgsConstructor
public class ImageController {

  private static final MediaType JPEG_MEDIA_TYPE = MediaType.IMAGE_JPEG;

  private final ImageService imageService;

  @GetMapping("/{imageId}")
  public ResponseEntity<byte[]> getImage(@PathVariable UUID imageId) {
    var imageOpt = imageService.findById(imageId);

    if (imageOpt.isEmpty()) {
      return ResponseEntity.notFound().build();
    }

    try {
      var imageData = imageService.readImageFile(imageOpt.get());

      return ResponseEntity.ok()
          .contentType(JPEG_MEDIA_TYPE)
          .cacheControl(
              CacheControl.maxAge(java.time.Duration.ofDays(365)).cachePublic().immutable())
          .eTag(imageId.toString())
          .body(imageData);
    } catch (IOException e) {
      log.error("Failed to read image file for id: {}", imageId, e);
      return ResponseEntity.notFound().build();
    }
  }
}
