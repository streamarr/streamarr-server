package com.streamarr.server.rest.pagination;

import java.util.List;

public record JsonApiErrorResponse(List<JsonApiError> errors) {}
