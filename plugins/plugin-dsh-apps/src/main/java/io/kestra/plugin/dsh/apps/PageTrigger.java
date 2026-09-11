package io.kestra.plugin.dsh.apps;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/**
 * Declares an Amis page route for the flow: {@code GET /{namespace}/{appName}/{pageId}}
 * (the namespace is the declaring flow's own namespace, e.g. {@code dsh.apps}) serves
 * the Amis schema referenced by {@code amis} (a {@code nsfile:///} URI in the flow's
 * own namespace). The namespace is part of the route key, so two flows in different
 * namespaces may declare the same appName/pageId without colliding.
 *
 * <p>Routing-only trigger (directly extends {@link AbstractTrigger}): it is never
 * picked up by the scheduler ({@code TriggerType.from} returns null) and must
 * <b>not</b> extend {@code AbstractWebhookTrigger} — that would expose an
 * {@code @AnonymousAccess} webhook route bypassing OIDC.
 */
@SuperBuilder(toBuilder = true)
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Getter
@Plugin(
    examples = {
        @Example(
            title = "Serve an Amis form page at /company.team/hello/form",
            full = true,
            code = """
                id: hello-app
                namespace: company.team

                tasks:
                  - id: greet
                    type: io.kestra.plugin.core.log.Log
                    message: Hello

                triggers:
                  - id: hello_form
                    type: io.kestra.plugin.dsh.apps.PageTrigger
                    appName: hello
                    pageId: form
                    amis: nsfile:///apps/hello-form.json
                """
        )
    }
)
public class PageTrigger extends AbstractTrigger {

    @NotBlank
    @PluginProperty
    @Schema(title = "App name — second path segment of the route (/{namespace}/{appName}/{pageId}).")
    private String appName;

    @NotBlank
    @PluginProperty
    @Schema(title = "Page id — third path segment of the route (/{namespace}/{appName}/{pageId}).")
    private String pageId;

    @NotBlank
    @PluginProperty
    @Schema(
        title = "Amis schema location.",
        description = "A `nsfile:///` URI (three slashes = current namespace, no parent inheritance) pointing to the JSON schema served for this page. Cross-namespace forms (`nsfile://other.ns/...`) are rejected."
    )
    private String amis;
}
