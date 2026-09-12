package com.streamarr.server.config;

import com.github.kagkarlsson.scheduler.serializer.Serializer;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Serializes db-scheduler task data with Jackson 3. The library's own Jackson serializer targets
 * Jackson 2, which this application does not ship.
 */
public final class JacksonTaskDataSerializer implements Serializer {

  private final JsonMapper mapper = JsonMapper.builder().build();

  @Override
  public byte[] serialize(Object data) {
    try {
      return mapper.writeValueAsBytes(data);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Task data cannot be serialized", exception);
    }
  }

  @Override
  public <T> T deserialize(Class<T> type, byte[] serialized) {
    try {
      return mapper.readValue(serialized, type);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Task data cannot be deserialized", exception);
    }
  }
}
