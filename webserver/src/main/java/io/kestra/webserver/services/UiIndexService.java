package io.kestra.webserver.services;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

import io.kestra.webserver.configuration.WebserverConfiguration;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.cookie.Cookie;
import io.micronaut.http.cookie.SameSite;
import io.micronaut.security.csrf.CsrfConfiguration;
import io.micronaut.security.csrf.generator.CsrfTokenGenerator;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Serves the UI {@code index.html} and the standalone dsh Apps shell {@code apps.html}: each file is
 * read from the classpath and rewritten (base path, analytics, title, custom head) only once into an
 * immutable template; per request only the CSRF meta tag is inserted.
 * <p>
 * The response carries the user's CSRF token, so it is never cacheable and gets no entity tag.
 */
@Singleton
@Requires(property = "kestra.webserver.ui.enabled", notEquals = "false", defaultValue = "true")
public class UiIndexService {
    private static final String INDEX_RESOURCE = "ui/index.html";
    private static final String APPS_RESOURCE = "ui/apps.html";
    private static final String APPS_EDITOR_RESOURCE = "ui/apps-editor.html";
    private static final String HEAD_TAG = "<head>";
    // 'private' keeps a shared cache from ever storing another user's token; 'no-store' would also
    // disqualify the page from the browser back/forward cache.
    private static final String CACHE_CONTROL = "no-cache, private";

    private final String basePath;
    private final WebserverConfiguration webserverConfiguration;
    private final Optional<CsrfConfiguration> csrfConfiguration;
    private final Optional<CsrfTokenGenerator<HttpRequest<?>>> csrfTokenGenerator;

    // Empty when the UI is not packaged on the classpath (backend-only builds).
    private final Optional<String> template;
    private final Optional<String> appsTemplate;
    private final Optional<String> appsEditorTemplate;

    @Inject
    public UiIndexService(
        @Nullable @Value("${micronaut.server.context-path}") String basePath,
        WebserverConfiguration webserverConfiguration,
        Optional<CsrfConfiguration> csrfConfiguration,
        Optional<CsrfTokenGenerator<HttpRequest<?>>> csrfTokenGenerator
    ) {
        this.basePath = basePath;
        this.webserverConfiguration = Objects.requireNonNull(webserverConfiguration);
        this.csrfConfiguration = Objects.requireNonNull(csrfConfiguration);
        this.csrfTokenGenerator = Objects.requireNonNull(csrfTokenGenerator);
        this.template = load(INDEX_RESOURCE);
        this.appsTemplate = load(APPS_RESOURCE);
        this.appsEditorTemplate = load(APPS_EDITOR_RESOURCE);
    }

    /**
     * Renders the full {@code index.html} response for the given request, or empty when the UI is not
     * packaged on the classpath.
     */
    public Optional<MutableHttpResponse<byte[]>> render(HttpRequest<?> request) {
        return template.map(html -> render(request, html));
    }

    /**
     * Renders the standalone dsh Apps shell ({@code apps.html}) for the given request, or empty when
     * the UI is not packaged on the classpath.
     */
    public Optional<MutableHttpResponse<byte[]>> renderApps(HttpRequest<?> request) {
        return appsTemplate.map(html -> render(request, html));
    }

    /**
     * Renders the standalone dsh Apps editor shell ({@code apps-editor.html}) for the given request,
     * or empty when the UI is not packaged on the classpath. Same per-request CSRF meta injection as
     * {@link #renderApps} (lessons-learned #1: the meta must match the login session cookie).
     */
    public Optional<MutableHttpResponse<byte[]>> renderAppEditor(HttpRequest<?> request) {
        return appsEditorTemplate.map(html -> render(request, html));
    }

    private MutableHttpResponse<byte[]> render(HttpRequest<?> request, String template) {
        String html = template;
        Cookie csrfCookie = null;

        if (csrfConfiguration.isPresent() && csrfTokenGenerator.isPresent()) {
            // Reuse the existing cookie token so multiple tabs and BFCache-restored pages
            // all share one stable token. Generate only when the cookie is absent.
            String csrfToken = request.getCookies()
                .findCookie(csrfConfiguration.get().getCookieName())
                .map(Cookie::getValue)
                .orElseGet(() -> csrfTokenGenerator.get().generateCsrfToken(request));

            if (csrfToken != null) {
                String escaped = csrfToken.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
                html = withCsrfMeta(template, "<meta name=\"csrf-token\" content=\"" + escaped + "\">");
                csrfCookie = Cookie.of(csrfConfiguration.get().getCookieName(), csrfToken)
                    .httpOnly(true)
                    .secure(request.isSecure())
                    .sameSite(SameSite.Strict)
                    .path("/");
            }
        }

        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        MutableHttpResponse<byte[]> response = HttpResponse.ok(body)
            .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
            .contentType(MediaType.TEXT_HTML_TYPE)
            .contentLength(body.length);
        if (csrfCookie != null) {
            response.cookie(csrfCookie);
        }
        return response;
    }

    // Plain concatenation rather than replaceFirst: a token is untrusted input for a regex replacement.
    private static String withCsrfMeta(String html, String metaTag) {
        int headIndex = html.indexOf(HEAD_TAG);
        if (headIndex < 0) {
            return html;
        }
        int insertionPoint = headIndex + HEAD_TAG.length();
        return html.substring(0, insertionPoint) + "\n" + metaTag + html.substring(insertionPoint);
    }

    private Optional<String> load(String resource) {
        try (InputStream is = UiIndexService.class.getClassLoader().getResourceAsStream(resource)) {
            if (is == null) {
                return Optional.empty();
            }
            return Optional.of(replace(new String(is.readAllBytes(), StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String replace(String line) {
        // Vite emits relative asset references (base: ""); both index.html and apps.html need them
        // rewritten to the absolute /ui/ mount so they resolve from any depth (a standalone app
        // page lives at /apps/... and must not resolve assets relative to it).
        line = line.replace("./", (basePath != null ? basePath : "") + "/ui/");

        if (!line.contains("KESTRA_UI_PATH")) {
            return line;
        }

        if (webserverConfiguration.googleAnalytics() != null) {
            line = line.replace("KESTRA_GOOGLE_ANALYTICS = null;", "KESTRA_GOOGLE_ANALYTICS = '" + webserverConfiguration.googleAnalytics() + "';");
        }

        if (webserverConfiguration.htmlTitle() != null) {
            line = line.replaceFirst("<title>(.*)</title>", "<title>" + webserverConfiguration.htmlTitle() + "</title>");
        }

        line = line.replace("<meta name=\"html-head\" content=\"replace\">", webserverConfiguration.htmlHead() == null ? "" : webserverConfiguration.htmlHead());

        return line;
    }
}
