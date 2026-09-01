package io.kestra.oidc.controllers;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Pure-helper tests for the IdP login page: same-origin {@code from} guard (open-redirect
 * protection) and HTML escaping of every interpolated value (XSS protection). The full
 * browser flow (Nacos SSO and the kestra-self bootstrap) is exercised live against the
 * docker-compose stack — see docs/oidc-provider.md.
 */
class OidcLoginControllerTest {

    @Test
    void fromGuardAcceptsSameOriginPathsOnly() {
        assertThat(OidcLoginController.sanitizeFrom("/oidc/authorize?client_id=nacos"),
            is("/oidc/authorize?client_id=nacos"));
        assertThat(OidcLoginController.sanitizeFrom("/ui/"), is("/ui/"));
        // Blank falls back to the UI landing page.
        assertThat(OidcLoginController.sanitizeFrom(null), is(OidcLoginController.DEFAULT_LANDING));
        assertThat(OidcLoginController.sanitizeFrom("  "), is(OidcLoginController.DEFAULT_LANDING));
    }

    @Test
    void fromGuardRejectsOpenRedirects() {
        // Absolute and protocol-relative targets must never survive sanitization.
        assertThat(OidcLoginController.sanitizeFrom("https://evil.example/steal"),
            is(OidcLoginController.DEFAULT_LANDING));
        assertThat(OidcLoginController.sanitizeFrom("//evil.example"),
            is(OidcLoginController.DEFAULT_LANDING));
        assertThat(OidcLoginController.sanitizeFrom("javascript:alert(1)"),
            is(OidcLoginController.DEFAULT_LANDING));
        assertThat(OidcLoginController.sanitizeFrom("oidc/authorize"),
            is(OidcLoginController.DEFAULT_LANDING));
    }

    @Test
    void loginPageEscapesFromValue() {
        String html = OidcLoginController.loginPageHtml(
            "/oidc/authorize?x=\"><script>alert(1)</script>", false);
        assertThat(html, not(containsString("<script>")));
        assertThat(html, containsString("&lt;script&gt;"));
        assertThat(html, containsString("action=\"/oidc/login\""));
    }

    @Test
    void loginPageShowsErrorOnlyOnFailure() {
        assertThat(OidcLoginController.loginPageHtml("/ui/", true), containsString("用户名或密码错误"));
        assertThat(OidcLoginController.loginPageHtml("/ui/", false), not(containsString("用户名或密码错误")));
        // The escaped empty error paragraph keeps layout stable.
        assertThat(OidcLoginController.loginPageHtml("/ui/", false), containsString("class=\"error\""));
    }

    @Test
    void loginPageIsSelfContained() {
        String html = OidcLoginController.loginPageHtml("/ui/", false);
        // No external assets: the page renders identically offline.
        assertThat(html, allOf(
            containsString("<!DOCTYPE html>"),
            containsString("name=\"username\""),
            containsString("name=\"password\""),
            containsString("name=\"from\"")));
        assertThat(html, not(containsString("src=\"http")));
        assertThat(html, not(containsString("href=\"http")));
    }
}
