package com.streamarr.server.services.library;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.mizosoft.methanol.BodyAdapter;
import com.github.mizosoft.methanol.adapter.ForwardingDecoder;
import com.github.mizosoft.methanol.adapter.ForwardingEncoder;
import com.github.mizosoft.methanol.adapter.jackson.JacksonAdapterFactory;
import com.google.auto.service.AutoService;

public class JacksonJsonAdapters {
    private static final ObjectMapper mapper = new ObjectMapper();

    @AutoService(BodyAdapter.Encoder.class)
    public static class Encoder extends ForwardingEncoder {
        public Encoder() {
            super(JacksonAdapterFactory.createJsonEncoder(mapper));
        }
    }

    @AutoService(BodyAdapter.Decoder.class)
    public static class Decoder extends ForwardingDecoder {
        public Decoder() {
            super(JacksonAdapterFactory.createJsonDecoder(mapper));
        }
    }
}
