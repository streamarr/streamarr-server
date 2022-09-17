/*
 * This file is generated by jOOQ.
 */
package com.streamarr.server.jooq.generated.routines;


import com.streamarr.server.jooq.generated.Public;

import java.util.UUID;

import org.jooq.Parameter;
import org.jooq.impl.AbstractRoutine;
import org.jooq.impl.Internal;
import org.jooq.impl.SQLDataType;


/**
 * This class is generated by jOOQ.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class UuidGenerateV1mc extends AbstractRoutine<UUID> {

    private static final long serialVersionUID = 1L;

    /**
     * The parameter <code>public.uuid_generate_v1mc.RETURN_VALUE</code>.
     */
    public static final Parameter<UUID> RETURN_VALUE = Internal.createParameter("RETURN_VALUE", SQLDataType.UUID, false, false);

    /**
     * Create a new routine call instance
     */
    public UuidGenerateV1mc() {
        super("uuid_generate_v1mc", Public.PUBLIC, SQLDataType.UUID);

        setReturnParameter(RETURN_VALUE);
    }
}
