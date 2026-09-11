package io.kestra.webserver.filter;

import org.reactivestreams.Publisher;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import io.micronaut.http.filter.ServerFilterPhase;
import reactor.core.publisher.Mono;

/**
 * dsh OOM defense (Kestra#17620 / micronaut-core#12940).
 *
 * <p>Root cause: a 4xx response written while a streaming (chunked, split-frame) request
 * body is still unconsumed makes Micronaut drain the remaining body on the keep-alive
 * connection; that drain path loops and appends {@code DelayedExecutionFlowImpl} nodes
 * without bound until OOM (reproduced with POST + Transfer-Encoding: chunked + headers
 * and body arriving in separate frames against any 404 route).
 *
 * <p>Defense: on every 4xx/5xx response, force {@code Connection: close}. The connection
 * is then not reused, Netty discards the unconsumed body instead of draining it, and the
 * drain loop never starts. 4xx/5xx traffic is a tiny fraction of requests, so the
 * per-connection cost is negligible. Layer-2 (route-miss 404) defense; controller-level
 * 404s are additionally fixed in AppRouterController (Kestra#17633 pattern).
 */
@Filter(Filter.MATCH_ALL_PATTERN)
public class CloseConnectionOnErrorFilter implements HttpServerFilter {
    @Override
    public int getOrder() {
        return ServerFilterPhase.LAST.order();
    }

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        return Mono.from(chain.proceed(request)).map(response -> {
            int status = response.getStatus().getCode();
            if (status >= 400) {
                response.header("Connection", "close");
            }
            return response;
        });
    }
}
