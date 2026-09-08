package io.kestra.webserver.services.ai.openai;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import io.kestra.core.docs.JsonSchemaGenerator;
import io.kestra.core.plugins.PluginRegistry;
import io.kestra.core.services.ExpressionContextService;
import io.kestra.core.services.FlowParsingService;
import io.kestra.core.services.InstanceService;
import io.kestra.core.utils.VersionProvider;
import io.kestra.webserver.services.ai.AiService;
import io.kestra.webserver.services.ai.NamespaceContextTool;
import io.kestra.webserver.services.posthog.PosthogService;
import io.kestra.webserver.utils.HttpClientUtils;

import dev.langchain4j.http.client.HttpClientBuilderLoader;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import io.micronaut.core.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

/**
 * Chat service for any OpenAI-compatible provider. The configured {@code baseUrl} may point at
 * OpenAI itself or at any compatible endpoint (DeepSeek, Ollama, an internal AI gateway, ...),
 * which is what lets OSS run models that upstream would require the Enterprise Edition for.
 */
@Slf4j
public class OpenAiAiService extends AiService<OpenAiConfiguration> {
    public static final String TYPE = "openai";

    public OpenAiAiService(PluginRegistry pluginRegistry, JsonSchemaGenerator jsonSchemaGenerator, VersionProvider versionProvider, InstanceService instanceService,
        PosthogService posthogService, @Nullable NamespaceContextTool namespaceContextTool,
        String displayName, List<ChatModelListener> listeners, OpenAiConfiguration openAiConfiguration, ExpressionContextService expressionContextService,
        FlowParsingService flowParsingService) {
        super(
            pluginRegistry, jsonSchemaGenerator, versionProvider, instanceService, posthogService, namespaceContextTool, TYPE, displayName, listeners, openAiConfiguration,
            expressionContextService, flowParsingService
        );
    }

    private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";

    @Override
    protected String baseUrl() {
        return getAiConfiguration().baseUrl() != null ? getAiConfiguration().baseUrl() : DEFAULT_BASE_URL;
    }

    public ChatModel chatModel(List<ChatModelListener> listeners) {
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
            .baseUrl(baseUrl())
            .listeners(listeners)
            .modelName(getAiConfiguration().modelName())
            .apiKey(getAiConfiguration().apiKey())
            .temperature(getAiConfiguration().temperature())
            .topP(getAiConfiguration().topP())
            .maxTokens(getAiConfiguration().maxTokens())
            .logRequests(getAiConfiguration().logRequests())
            .logResponses(getAiConfiguration().logResponses())
            .customHeaders(getAiConfiguration().customHeaders())
            .timeout(getAiConfiguration().timeout());

        if (getAiConfiguration().clientPem() != null) {
            try (
                ByteArrayInputStream is = new ByteArrayInputStream(getAiConfiguration().clientPem().getBytes(StandardCharsets.UTF_8));
                ByteArrayInputStream caPem = getAiConfiguration().caPem() == null ? null : new ByteArrayInputStream(getAiConfiguration().caPem().getBytes(StandardCharsets.UTF_8))
            ) {
                JdkHttpClientBuilder jdkHttpClientBuilder = ((JdkHttpClientBuilder) HttpClientBuilderLoader.loadHttpClientBuilder()).httpClientBuilder(
                    HttpClientUtils.withPemCertificate(is, caPem)
                );

                builder = builder.httpClientBuilder(jdkHttpClientBuilder);
            } catch (Exception e) {
                throw new IllegalArgumentException("Exception while trying to setup AI Service certificates", e);
            }
        }

        return builder.build();
    }

    @Override
    public StreamingChatModel streamingChatModel(List<ChatModelListener> listeners) {
        OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder = OpenAiStreamingChatModel.builder()
            .baseUrl(baseUrl())
            .listeners(listeners)
            .modelName(getAiConfiguration().modelName())
            .apiKey(getAiConfiguration().apiKey())
            .temperature(getAiConfiguration().temperature())
            .topP(getAiConfiguration().topP())
            .maxTokens(getAiConfiguration().maxTokens())
            .logRequests(getAiConfiguration().logRequests())
            .logResponses(getAiConfiguration().logResponses())
            .customHeaders(getAiConfiguration().customHeaders())
            .timeout(getAiConfiguration().timeout());

        if (getAiConfiguration().clientPem() != null) {
            try (
                ByteArrayInputStream is = new ByteArrayInputStream(getAiConfiguration().clientPem().getBytes(StandardCharsets.UTF_8));
                ByteArrayInputStream caPem = getAiConfiguration().caPem() == null ? null : new ByteArrayInputStream(getAiConfiguration().caPem().getBytes(StandardCharsets.UTF_8))
            ) {
                JdkHttpClientBuilder jdkHttpClientBuilder = ((JdkHttpClientBuilder) HttpClientBuilderLoader.loadHttpClientBuilder()).httpClientBuilder(
                    HttpClientUtils.withPemCertificate(is, caPem)
                );

                builder = builder.httpClientBuilder(jdkHttpClientBuilder);
            } catch (Exception e) {
                throw new IllegalArgumentException("Exception while trying to setup AI Service certificates", e);
            }
        }

        return builder.build();
    }
}
