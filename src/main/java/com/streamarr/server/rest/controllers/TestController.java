package com.streamarr.server.rest.controllers;

import com.streamarr.server.services.library.video.VideoResolutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class TestController {

    private final VideoResolutionService videoResolutionService;

    // TODO: Delete. Using for executing some logic.
    @PostMapping("/test")
    public void test() {
        videoResolutionService.parsePaths();
    }
}
