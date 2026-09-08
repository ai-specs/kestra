package io.kestra.webserver.services.ai.openai;

import java.time.Duration;
import java.util.Map;

import io.kestra.webserver.services.ai.AiConfiguration;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.bind.annotation.Bindable;
import io.micronaut.core.convert.format.MapFormat;
import io.micronaut.core.naming.conventions.StringConvention;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Configuration for an OpenAI-compatible chat provider (any vendor exposing the OpenAI chat
 * completions API, e.g. OpenAI itself, DeepSeek, Ollama, or a gateway). The {@code baseUrl}
 * lets a deployment point the provider at any OpenAI-compatible endpoint.
 */
public record OpenAiConfiguration(
    @Nullable String baseUrl,
    String apiKey,
    @Bindable(defaultValue = "gpt-4o-mini") String modelName,
    @Bindable(defaultValue = "0.7") Double temperature,
    @Nullable Double topP,
    @Nullable Integer maxTokens,
    @Nullable String clientPem,
    @Schema(description = "Not required but can be useful to add further trust", nullable = true)
    @Nullable String caPem,
    @Bindable(defaultValue = "false") boolean logRequests,
    @Bindable(defaultValue = "false") boolean logResponses,
    @Nullable
    @MapFormat(transformation = MapFormat.MapTransformation.FLAT, keyFormat = StringConvention.RAW) Map<String, String> customHeaders,
    @Nullable Duration timeout) implements AiConfiguration {
    public OpenAiConfiguration {
        if (modelName == null)
            modelName = "gpt-4o-mini";
        if (temperature == null)
            temperature = 0.7;
    }

    @Override
    public String type() {
        return "openai";
    }
}
