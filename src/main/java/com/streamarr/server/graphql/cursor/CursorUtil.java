package com.streamarr.server.graphql.cursor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.relay.DefaultConnectionCursor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class CursorUtil {

    private final ObjectMapper jacksonObjectMapper;

    public DefaultConnectionCursor encodeMediaCursor(MediaPaginationOptions mediaPaginationOptions, UUID cursorId, Object sortValue) {
        try {
            mediaPaginationOptions = MediaPaginationOptions.builder()
                .cursorId(cursorId)
                .mediaFilter(mediaPaginationOptions.getMediaFilter().toBuilder()
                    .previousSortFieldValue(sortValue)
                    .build())
                .build();

            String jsonStr = jacksonObjectMapper.writeValueAsString(mediaPaginationOptions);
            return new DefaultConnectionCursor(Base64.getEncoder().encodeToString(jsonStr.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public MediaPaginationOptions decodeMediaCursor(PaginationOptions options) {

        if (options.getCursor().isEmpty()) {
            throw new RuntimeException("Cannot decode an empty cursor.");
        }

        var cursor = options.getCursor().get();

        try {
            var jsonStr = new String(Base64.getDecoder().decode(cursor));
            return jacksonObjectMapper.readValue(jsonStr, MediaPaginationOptions.class)
                .toBuilder()
                .paginationOptions(options)
                .build();

        } catch (JsonProcessingException exception) {
            var msg = "Could not decode cursor '" + cursor + "' into " + MediaPaginationOptions.class.getSimpleName();
            throw new RuntimeException(msg);
        }
    }
}
