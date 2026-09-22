package io.kestra.oidc.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.core.serializers.JacksonMapper;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;

/**
 * Micronaut 5 is Jackson 3 based and no longer registers a Jackson 2
 * {@code com.fasterxml.jackson.databind.ObjectMapper} bean (Kestra's own
 * Jackson hub stays on 2.x, see upstream ObjectMapperFactory). The OIDC
 * module's JWT / authorization-code services serialize with Jackson 2, so
 * provide that mapper explicitly.
 */
@Factory
public class Jackson2ObjectMapperFactory {

    @Singleton
    public ObjectMapper objectMapper() {
        return JacksonMapper.ofJson();
    }
}
