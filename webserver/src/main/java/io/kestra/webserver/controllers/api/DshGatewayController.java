package io.kestra.webserver.controllers.api;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;

/**
 * dsh API gateway (dsh.docx: dsh(PC) ←业务调用（经 Kestra 安全网关鉴权）→ 企业原有系统).
 *
 * dsh (PC) never calls enterprise systems (OA/CRM/ERP) directly: every business
 * call goes through this authenticated proxy, which validates the dsh gateway
 * token, forwards to the configured enterprise base URL, and audits the call.
 */
@Controller("/api/v1/dsh/gateway")
@ExecuteOn(TaskExecutors.IO)
public class DshGatewayController {

    private static final Logger LOG = LoggerFactory.getLogger(DshGatewayController.class);

    private final DshGatewayConfiguration configuration;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    public DshGatewayController(DshGatewayConfiguration configuration) {
        this.configuration = configuration;
    }

    /**
     * Forward a business call to an enterprise system (OA/CRM/ERP).
     *
     * @param authorization Provider-issued Bearer token (aud=dsh, enforced by OidcBearerAuthFilter)
     * @param system target system alias registered in the configuration (e.g. enterprise)
     * @param path business path inside the target system, e.g. crm/customer
     * @param query optional raw query string forwarded to the target
     * @param body JSON payload
     */
    @Post(uri = "/{system}/{path:.*}")
    @Operation(summary = "Authenticated proxy for enterprise system business calls (audited)")
    public HttpResponse<String> forward(
        @Parameter(description = "dsh session id, propagated as trace id across the whole chain (dsh.docx 第十五章)")
        @Header(value = "X-Dsh-Trace-Id") String traceId,
        @Parameter(description = "Target system alias") String system,
        @Parameter(description = "Business path inside the target system") String path,
        @Parameter(description = "Raw query string forwarded to the target") @QueryValue(defaultValue = "") String query,
        @Body String body
    ) throws Exception {
        // 鉴权：OidcBearerAuthFilter（Bearer + aud=dsh 受众校验）。
        String base = configuration.systemBaseUrl(system);
        if (base == null) {
            LOG.info("[dsh-gateway] DENIED system={} path={} status=404 (unknown system)", system, path);
            return HttpResponse.notFound("{\"error\":\"unknown system\"}");
        }

        String target = base + "/" + path + (query == null || query.isBlank() ? "" : "?" + query);
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(target))
            .timeout(Duration.ofSeconds(configuration.timeoutSeconds()))
            .header("Content-Type", "application/json")
            .header("Authorization", configuration.systemAuthorization())
            .header("X-Dsh-Trace-Id", traceId == null ? "" : traceId)
            .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body))
            .build();

        java.net.http.HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
        LOG.info("[dsh-gateway] FORWARDED system={} path={} status={}", system, path, response.statusCode());
        return HttpResponse.ok(response.body());
    }

    /** Gateway settings resolved from the deployment configuration. */
    @Singleton
    public static class DshGatewayConfiguration {

        private final String enterpriseBaseUrl;
        private final String enterpriseToken;

        public DshGatewayConfiguration(
            @io.micronaut.context.annotation.Value("${dsh.gateway.systems.enterprise.base-url}") String enterpriseBaseUrl,
            @io.micronaut.context.annotation.Value("${dsh.gateway.enterprise-token}") String enterpriseToken
        ) {
            this.enterpriseBaseUrl = enterpriseBaseUrl;
            this.enterpriseToken = enterpriseToken;
        }

        /** Resolve a system alias to its base URL; null when the alias is unknown. */
        public String systemBaseUrl(String alias) {
            return "enterprise".equals(alias) ? enterpriseBaseUrl : null;
        }

        /** Authorization header used when calling the enterprise system. */
        public String systemAuthorization() {
            return "Bearer " + enterpriseToken;
        }

        public int timeoutSeconds() {
            return 10;
        }
    }
}
