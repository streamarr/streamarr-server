/*
 * This file is generated by jOOQ.
 */
package com.streamarr.server.jooq.generated.enums;


import com.streamarr.server.jooq.generated.Public;

import org.jooq.Catalog;
import org.jooq.EnumType;
import org.jooq.Schema;


/**
 * This class is generated by jOOQ.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public enum ExternalAgentStrategy implements EnumType {

    TMDB("TMDB");

    private final String literal;

    private ExternalAgentStrategy(String literal) {
        this.literal = literal;
    }

    @Override
    public Catalog getCatalog() {
        return getSchema().getCatalog();
    }

    @Override
    public Schema getSchema() {
        return Public.PUBLIC;
    }

    @Override
    public String getName() {
        return "external_agent_strategy";
    }

    @Override
    public String getLiteral() {
        return literal;
    }

    /**
     * Lookup a value of this EnumType by its literal
     */
    public static ExternalAgentStrategy lookupLiteral(String literal) {
        return EnumType.lookupLiteral(ExternalAgentStrategy.class, literal);
    }
}
