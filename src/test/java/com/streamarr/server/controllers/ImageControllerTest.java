package com.streamarr.server.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.IF_NONE_MATCH;
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
  @DisplayName("Should allow private long-lived caching when image exists")
  void shouldAllowPrivateLongLivedCachingWhenImageExists() throws Exception {
    var image = createImageWithFile(new byte[] {1, 2, 3});

    var result =
        mockMvc
            .perform(get("/api/images/{imageId}", image.getId()))
            .andExpect(status().isOk())
            .andReturn();

    assertThat(result.getResponse().getHeader("Cache-Control"))
        .isEqualTo("max-age=31536000, private, immutable");
  }

  @Test
  @DisplayName("Should return 304 without a body when If-None-Match carries the current ETag")
  void shouldReturn304WithoutBodyWhenIfNoneMatchCarriesCurrentETag() throws Exception {
    var image = createImageWithFile(new byte[] {1, 2, 3});
    var currentETag = quoted(image.getId());

    var result =
        mockMvc
            .perform(get("/api/images/{imageId}", image.getId()).header(IF_NONE_MATCH, currentETag))
            .andExpect(status().isNotModified())
            .andReturn();

    assertThat(result.getResponse().getContentAsByteArray()).isEmpty();
    assertThat(result.getResponse().getHeaders("ETag")).containsExactly(currentETag);
  }

  @Test
  @DisplayName("Should return the full image when If-None-Match carries a stale ETag")
  void shouldReturnFullImageWhenIfNoneMatchCarriesStaleETag() throws Exception {
    var imageData = new byte[] {1, 2, 3};
    var image = createImageWithFile(imageData);

    var result =
        mockMvc
            .perform(
                get("/api/images/{imageId}", image.getId())
                    .header(IF_NONE_MATCH, quoted(UUID.randomUUID())))
            .andExpect(status().isOk())
            .andReturn();

    assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(imageData);
  }

  @Test
  @DisplayName("Should keep the caching headers on a 304 so the client can revalidate again")
  void shouldKeepCachingHeadersOnNotModified() throws Exception {
    var image = createImageWithFile(new byte[] {1, 2, 3});

    var result =
        mockMvc
            .perform(
                get("/api/images/{imageId}", image.getId())
                    .header(IF_NONE_MATCH, quoted(image.getId())))
            .andExpect(status().isNotModified())
            .andReturn();

    assertThat(result.getResponse().getHeader("Cache-Control"))
        .isEqualTo("max-age=31536000, private, immutable");
  }

  @Test
  @DisplayName("Should revalidate without reading the image file")
  void shouldRevalidateWithoutReadingTheImageFile() throws Exception {
    var image = createImageWithoutFile();

    mockMvc
        .perform(
            get("/api/images/{imageId}", image.getId())
                .header(IF_NONE_MATCH, quoted(image.getId())))
        .andExpect(status().isNotModified());
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

  @Test
  @DisplayName("Should return 500 when image file cannot be read")
  void shouldReturn500WhenImageFileCannotBeRead() throws Exception {
    var image = createImageWithoutFile();

    mockMvc
        .perform(get("/api/images/{imageId}", image.getId()))
        .andExpect(status().isInternalServerError());
  }

  private Image createImageWithFile(byte[] data) throws IOException {
    var image = createImageWithoutFile();
    var absolutePath = fileSystem.getPath("/data/images").resolve(image.getPath());

    Files.createDirectories(absolutePath.getParent());
    Files.write(absolutePath, data);

    return image;
  }

  private Image createImageWithoutFile() {
    var entityId = UUID.randomUUID();

    return imageRepository.save(
        Image.builder()
            .entityId(entityId)
            .entityType(ImageEntityType.MOVIE)
            .imageType(ImageType.POSTER)
            .variant(ImageSize.SMALL)
            .width(185)
            .height(278)
            .path("movie/" + entityId + "/poster/small.jpg")
            .build());
  }

  private static String quoted(UUID imageId) {
    return "\"" + imageId + "\"";
  }
}
