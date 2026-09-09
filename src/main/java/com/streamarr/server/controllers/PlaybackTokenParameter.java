package com.streamarr.server.controllers;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Documents the filter-resolved playback credential on every endpoint in a controller. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Parameter(
    name = "t",
    in = ParameterIn.QUERY,
    required = true,
    description = "Playback token bound to this stream session",
    schema = @Schema(type = "string"))
@interface PlaybackTokenParameter {}
