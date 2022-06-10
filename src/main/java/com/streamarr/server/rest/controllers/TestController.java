package com.streamarr.server.rest.controllers;

import com.streamarr.server.services.VideoFileParsingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class TestController {

    private final VideoFileParsingService videoFileParsingService;

    // TODO: Delete. Using for executing some logic.
    @PostMapping("/test")
    public void test() {
        videoFileParsingService.parsePaths();
    }
}