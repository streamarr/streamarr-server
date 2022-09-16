package com.streamarr.server.services.metadata;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import static org.springframework.beans.factory.config.BeanDefinition.SCOPE_PROTOTYPE;

@Component
@Scope(SCOPE_PROTOTYPE)
@RequiredArgsConstructor
public class ImageThumbnailWorkerVerticle extends AbstractVerticle {

    public static final String IMAGE_THUMBNAIL_PROCESSOR = "image.thumbnail.processor";

    private final Logger log;
    private final ImageThumbnailService imageThumbnailService;

    @Override
    public void start() throws Exception {
        log.info("[Worker] Starting in: {}", Thread.currentThread().getName());

        super.start();

        vertx.eventBus().consumer(IMAGE_THUMBNAIL_PROCESSOR, this::imageHandler);
    }

    private void imageHandler(Message<Buffer> message) {
        try {
            var thumbnailData = imageThumbnailService.convertImageToThumbnails(message.body().getBytes());
            message.reply(Buffer.buffer(thumbnailData));
        } catch (RuntimeException ex) {
            message.fail(0, ex.getMessage());
        }
    }
}
