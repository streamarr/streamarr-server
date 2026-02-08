package com.streamarr.server.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.streamarr.server.config.ImageProperties;
import com.streamarr.server.domain.media.Image;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageSize;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.fakes.FakeImageRepository;
import com.streamarr.server.services.ImageService;
import com.streamarr.server.services.metadata.ImageVariantService;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@Tag("UnitTest")
@DisplayName("Image Controller Tests")
class ImageControllerTest {

  private MockMvc mockMvc;
  private FakeImageRepository imageRepository;
  private FileSystem fileSystem;

  @BeforeEach
  void setUp() {
    imageRepository = new FakeImageRepository();
    fileSystem = Jimfs.newFileSystem(Configuration.unix());
    var imageProperties = new ImageProperties("/data/images");
    var imageVariantService = new ImageVariantService();
    var imageService =
        new ImageService(imageRepository, imageVariantService, imageProperties, fileSystem);
    var controller = new ImageController(imageService);
    mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
  }

  @Test
  @DisplayName("Should return JPEG bytes with correct content type when image exists")
  void shouldReturnJpegBytesWithCorrectContentTypeWhenImageExists() throws Exception {
    var imageData = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    var image = createImageWithFile(imageData);

    var result =
        mockMvc
            .perform(get("/api/images/{imageId}", image.getId()))
            .andExpect(status().isOk())
            .andReturn();

    assertThat(result.getResponse().getContentType()).isEqualTo("image/jpeg");
    assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(imageData);
  }

  @Test
  @DisplayName("Should return 404 when image not found")
  void shouldReturn404WhenImageNotFound() throws Exception {
    mockMvc
        .perform(get("/api/images/{imageId}", UUID.randomUUID()))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("Should include immutable cache control header when image exists")
  void shouldIncludeImmutableCacheControlHeaderWhenImageExists() throws Exception {
    var image = createImageWithFile(new byte[] {1, 2, 3});

    var result =
        mockMvc
            .perform(get("/api/images/{imageId}", image.getId()))
            .andExpect(status().isOk())
            .andReturn();

    var cacheControl = result.getResponse().getHeader("Cache-Control");
    assertThat(cacheControl).contains("max-age=31536000");
    assertThat(cacheControl).contains("public");
    assertThat(cacheControl).contains("immutable");
  }

  @Test
  @DisplayName("Should include ETag header when image exists")
  void shouldIncludeETagHeaderWhenImageExists() throws Exception {
    var image = createImageWithFile(new byte[] {1, 2, 3});

    var result =
        mockMvc
            .perform(get("/api/images/{imageId}", image.getId()))
            .andExpect(status().isOk())
            .andReturn();

    assertThat(result.getResponse().getHeader("ETag")).contains(image.getId().toString());
  }

  private Image createImageWithFile(byte[] data) throws IOException {
    var entityId = UUID.randomUUID();
    var relativePath = "movie/" + entityId + "/poster/small.jpg";
    var absolutePath = fileSystem.getPath("/data/images").resolve(relativePath);
    Files.createDirectories(absolutePath.getParent());
    Files.write(absolutePath, data);

    return imageRepository.save(
        Image.builder()
            .entityId(entityId)
            .entityType(ImageEntityType.MOVIE)
            .imageType(ImageType.POSTER)
            .variant(ImageSize.SMALL)
            .width(185)
            .height(278)
            .path(relativePath)
            .build());
  }
}
