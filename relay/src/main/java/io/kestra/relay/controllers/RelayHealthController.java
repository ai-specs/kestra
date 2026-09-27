package io.kestra.relay.controllers;

import java.util.Map;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;

/**
 * Relay liveness probe (compose healthcheck). Anonymous: outside the Bearer-guarded surface
 * ({@code /api/v1/dsh/relay/**}), matching how the main Kestra server exposes {@code /health}.
 */
@Controller("/health")
public class RelayHealthController {

    @Get
    public HttpResponse<Map<String, Object>> health() {
        return HttpResponse.ok(Map.of("status", "UP", "service", "dsh-relay"));
    }
}
