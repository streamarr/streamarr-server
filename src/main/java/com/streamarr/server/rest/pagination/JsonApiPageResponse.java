package com.streamarr.server.rest.pagination;

import java.util.List;

public record JsonApiPageResponse(JsonApiLinks links, List<JsonApiResource> data) {}
