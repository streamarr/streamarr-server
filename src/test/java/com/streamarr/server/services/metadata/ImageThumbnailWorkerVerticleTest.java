package com.streamarr.server.services.metadata;

import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;


@DisplayName("Image Thumbnail Worker Verticle Tests")
@ExtendWith(VertxExtension.class)
@ExtendWith(MockitoExtension.class)
public class ImageThumbnailWorkerVerticleTest {

    @Mock
    private Logger mockLog;
    @Mock
    private ImageThumbnailService mockImageThumbnailService;

    @Test
    @DisplayName("Should deploy an Image Thumbnail Worker Verticle and fail request when exception thrown.")
    void useImageVerticleFailure(Vertx vertx, VertxTestContext testContext) {

        vertx.deployVerticle(new ImageThumbnailWorkerVerticle(mockLog, mockImageThumbnailService), new DeploymentOptions().setWorker(true))
            .onSuccess(id -> {
                var buffer = Buffer.buffer("test");

                when(mockImageThumbnailService.convertImageToThumbnails(any())).thenThrow(new RuntimeException("failed"));

                vertx.eventBus()
                    .request(ImageThumbnailWorkerVerticle.IMAGE_THUMBNAIL_PROCESSOR, buffer)
                    .onSuccess(s -> testContext.failNow("Should not complete"))
                    .onFailure(s -> {
                        testContext.verify(() -> {
                            assertThat(s.getMessage()).isEqualTo("failed");
                            testContext.completeNow();
                        });
                    });
            });
    }

    @Test
    @DisplayName("Should deploy an Image Thumbnail Worker Verticle and succeed request when bytes returned.")
    void useImageVerticleSuccess(Vertx vertx, VertxTestContext testContext) {

        vertx.deployVerticle(new ImageThumbnailWorkerVerticle(mockLog, mockImageThumbnailService), new DeploymentOptions().setWorker(true))
            .onSuccess(id -> {
                var buffer = Buffer.buffer("test");

                when(mockImageThumbnailService.convertImageToThumbnails(any())).thenReturn("test".getBytes());

                vertx.eventBus()
                    .request(ImageThumbnailWorkerVerticle.IMAGE_THUMBNAIL_PROCESSOR, buffer)
                    .onSuccess(s -> {
                        var bufferResult = (Buffer) s.body();

                        testContext.verify(() -> {
                            assertThat(new String(bufferResult.getBytes())).isEqualTo("test");
                            testContext.completeNow();
                        });
                    })
                    .onFailure(testContext::failNow);
            });
    }
}
