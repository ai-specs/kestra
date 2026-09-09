package io.kestra.plugin.dsh.apps;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.time.Duration;

/**
 * Declares an API route for the flow: {@code POST /apps/{appName}/{apiId}} creates
 * an execution of the declaring flow itself, with the request body mapped to the
 * flow's inputs.
 *
 * <p>Routing-only trigger (directly extends {@link AbstractTrigger}): it is never
 * picked up by the scheduler and must <b>not</b> extend
 * {@code AbstractWebhookTrigger} — that would expose an {@code @AnonymousAccess}
 * webhook route bypassing OIDC. Execution creation is handled by the webserver-side
 * {@code AppsService} (see docs/dsh-apps-amis.md).
 */
@SuperBuilder(toBuilder = true)
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Getter
@Plugin(
    examples = {
        @Example(
            title = "POST /apps/hello/submit runs the flow with the form inputs",
            full = true,
            code = """
                id: hello-app
                namespace: company.team

                inputs:
                  - id: user
                    type: STRING

                tasks:
                  - id: greet
                    type: io.kestra.plugin.core.log.Log
                    message: Hello {{ inputs.user }}

                triggers:
                  - id: hello_submit
                    type: io.kestra.plugin.dsh.apps.ApiTrigger
                    appName: hello
                    apiId: submit
                    responseMode: ASYNC
                    timeout: PT30S
                """
        )
    }
)
public class ApiTrigger extends AbstractTrigger {

    public enum ResponseMode {
        SYNC,
        ASYNC
    }

    @NotBlank
    @PluginProperty
    @Schema(title = "App name — first path segment of the route (/apps/{appName}/{apiId}).")
    private String appName;

    @NotBlank
    @PluginProperty
    @Schema(title = "Api id — second path segment of the route (/apps/{appName}/{apiId}).")
    private String apiId;

    @NotNull
    @Builder.Default
    @PluginProperty
    @Schema(
        title = "Response mode.",
        description = "ASYNC (default): POST returns 202 + executionId immediately, client polls the status endpoint. SYNC: POST waits for a terminal state (bounded by `timeout`) and returns outputs; on timeout or PAUSED it degrades to 202 + executionId."
    )
    private ResponseMode responseMode = ResponseMode.ASYNC;

    @Builder.Default
    @PluginProperty
    @Schema(
        title = "SYNC wait bound.",
        description = "How long a SYNC request waits for a terminal state before degrading to 202 + executionId. Ignored in ASYNC mode."
    )
    private Duration timeout = Duration.ofSeconds(30);
}
