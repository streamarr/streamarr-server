package com.streamarr.server.config.persistence;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Getter
@Component
public class PersistencePropertyResolver {

    private final String jdbcUrl;
    private final String username;
    private final String password;

    public PersistencePropertyResolver(
        @Value("${spring.datasource.url}") String jdbcUrl,
        @Value("${spring.datasource.username}") String username,
        @Value("${spring.datasource.password}") String password
    ) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }
}
