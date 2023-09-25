package com.streamarr.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;

@SpringBootApplication
@EnableRetry
public class StreamarrServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(StreamarrServerApplication.class, args);
    }

}
