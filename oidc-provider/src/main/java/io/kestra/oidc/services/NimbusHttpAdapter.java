package io.kestra.oidc.services;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.nimbusds.common.contenttype.ContentType;
import com.nimbusds.oauth2.sdk.http.HTTPRequest;

import io.micronaut.http.HttpRequest;

/**
 * Adapter between a Micronaut {@link HttpRequest} and the Nimbus SDK {@link HTTPRequest}.
 *
 * <p>
 * The Nimbus OAuth2 SDK is a request/response parsing library: its {@code parse(HTTPRequest)}
 * entry points operate on its own {@link HTTPRequest}. This adapter reconstructs one from the
 * Micronaut request, re-encoding the (already decoded) form parameters and copying the relevant
 * headers so that {@code TokenRequest}/{@code TokenIntrospectionRequest}/{@code TokenRevocationRequest}
 * parse correctly.
 */
public final class NimbusHttpAdapter {

    private NimbusHttpAdapter() {
    }

    /**
     * Builds a form-encoded {@code POST} Nimbus {@link HTTPRequest} from a Micronaut request.
     *
     * <p>
     * The Micronaut parameters (body + query, already URL-decoded) are re-encoded into a single
     * {@code application/x-www-form-urlencoded} body so the Nimbus parser can read them via
     * {@code getBodyAsFormParameters()}. The {@code Authorization} header is copied verbatim so
     * {@code client_secret_basic} authentication is preserved.
     *
     * @param request the Micronaut request
     * @param contentType the entity content type (usually {@code application/x-www-form-urlencoded})
     * @return the Nimbus HTTP request
     */
    public static HTTPRequest toNimbusFormPost(HttpRequest<?> request, ContentType contentType) {
        return toNimbusFormPost(request, Map.of(), contentType);
    }

    /**
     * Builds a form-encoded {@code POST} Nimbus {@link HTTPRequest} from a Micronaut request.
     *
     * <p>
     * The given form parameters (bound by Micronaut via {@code @Body Map<String,String>}) are
     * re-encoded into a single {@code application/x-www-form-urlencoded} body so the Nimbus parser
     * can read them via {@code getBodyAsFormParameters()}. The {@code Authorization} header is
     * copied verbatim so {@code client_secret_basic} authentication is preserved.
     *
     * @param request the Micronaut request (used for the URL and Authorization header)
     * @param form the already-bound form body parameters
     * @param contentType the entity content type (usually {@code application/x-www-form-urlencoded})
     * @return the Nimbus HTTP request
     */
    public static HTTPRequest toNimbusFormPost(HttpRequest<?> request, Map<String, String> form, ContentType contentType) {
        URI uri = absoluteUri(request);
        HTTPRequest nimbus = new HTTPRequest(HTTPRequest.Method.POST, uri);
        nimbus.setEntityContentType(contentType);
        nimbus.setBody(encodeParameters(form));

        String authorization = request.getHeaders().get("Authorization");
        if (authorization != null) {
            nimbus.setAuthorization(authorization);
        }
        return nimbus;
    }

    private static String encodeParameters(Map<String, String> parameters) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            if (!first) {
                sb.append('&');
            }
            first = false;
            sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            sb.append('=');
            sb.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /**
     * Returns an absolute URI for the request. Nimbus {@code parse(HTTPRequest)} rejects relative
     * URIs; Micronaut server requests carry a relative path, so the scheme/host/port are spliced in.
     */
    private static URI absoluteUri(HttpRequest<?> request) {
        URI uri = request.getUri();
        if (uri.isAbsolute()) {
            return uri;
        }
        // The Micronaut HttpRequest interface does not expose scheme/host directly; derive them from
        // the server address (and an optional X-Forwarded-Proto). Nimbus only requires an absolute
        // URL for parsing; the path is what matters here.
        String forwarded = request.getHeaders().get("X-Forwarded-Proto");
        String scheme = (forwarded != null && !forwarded.isBlank()) ? forwarded : "http";
        java.net.InetSocketAddress server = request.getServerAddress();
        String host = server.getHostString();
        int port = server.getPort();
        String prefix = scheme + "://" + host;
        if ((scheme.equalsIgnoreCase("http") && port != 80) || (scheme.equalsIgnoreCase("https") && port != 443)) {
            prefix += ":" + port;
        }
        String raw = uri.getRawPath() == null ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) {
            raw += "?" + uri.getRawQuery();
        }
        return URI.create(prefix + raw);
    }
}
