package com.streamarr.server.graphql.mutation.library;

import com.streamarr.server.domain.Library;
import java.util.List;

public record AddLibraryPayload(Library library, List<AddLibraryError> userErrors) {}
