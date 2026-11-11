package com.streamarr.server.graphql.inputs;

import java.util.List;

public record MarkNotificationsReadInput(List<String> ids) {}
