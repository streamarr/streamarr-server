package com.streamarr.server.rest.pagination;

import java.util.Map;

public record JsonApiResource(
    String type, String id, Map<String, Object> attributes, JsonApiResourceMeta meta) {}
