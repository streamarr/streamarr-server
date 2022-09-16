package com.streamarr.server.services.metadata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Image Thumbnail Service Tests")
@ExtendWith(MockitoExtension.class)
public class ImageThumbnailServiceTest {

    public static final String BASE_64_IMAGE = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

    @Mock
    private Logger mockLog;
    @InjectMocks
    private ImageThumbnailService imageThumbnailService;

    @Test
    @DisplayName("Should successfully convert image to a thumbnail image.")
    public void shouldConvertToThumbnail() {
        var imageOutput = imageThumbnailService.convertImageToThumbnails(Base64.getDecoder().decode(BASE_64_IMAGE));

        assertThat(imageOutput).isNotEmpty();
    }
}
