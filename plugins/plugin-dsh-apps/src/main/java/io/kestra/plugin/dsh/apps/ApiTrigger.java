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
            title = "One flow, several APIs: POST /apps/hello/{apiId} runs the flow with the form inputs; the flow branches on the injected {{ inputs.apiId }}",
            full = true,
            code = """
                id: hello-app
                namespace: company.team

                inputs:
                  - id: apiId
                    type: STRING
                  - id: user
                    type: STRING
                    required: false

                tasks:
                  - id: route
                    type: io.kestra.plugin.core.flow.Switch
                    value: "{{ inputs.apiId }}"
                    cases:
                      submit:
                        - id: greet
                          type: io.kestra.plugin.core.log.Log
                          message: Hello {{ inputs.user }}
                      query:
                        - id: query
                          type: io.kestra.plugin.core.log.Log
                          message: Query API called

                triggers:
                  - id: hello_submit
                    type: io.kestra.plugin.dsh.apps.ApiTrigger
                    appName: hello
                    apiId: submit
                    responseMode: ASYNC
                    responseBody: KESTRA
                    timeout: PT30S
                  - id: hello_query
                    type: io.kestra.plugin.dsh.apps.ApiTrigger
                    appName: hello
                    apiId: query
                    responseMode: SYNC
                    responseBody: AMIS
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

    /**
     * Shape of the HTTP response body returned by the route. Independent of
     * {@link ResponseMode} — the page scenario picks the mode, the body shape is
     * about who consumes the response.
     * <ul>
     *   <li>{@code KESTRA}: {@code {executionId, executionState, outputs, error}} —
     *   native shape for the standalone shell's custom fetcher. Uses the same
     *   {@code executionState} field name as AMIS, so a client reads the execution
     *   state from one field regardless of the format; the format itself is
     *   distinguishable by the presence of {@code status}/{@code data} (AMIS) versus
     *   {@code outputs}/{@code error} (KESTRA).</li>
     *   <li>{@code AMIS}: amis standard payload {@code {status, msg, data}} plus
     *   top-level {@code executionId} and {@code executionState} (both ignored by
     *   amis; they let an ASYNC client poll and tell a running execution from a
     *   finished one). {@code data} is always an object — the outputs map, or
     *   {@code {}} when there is nothing yet (never null or an empty string, as amis
     *   requires a key-value structure). Success is {@code {status: 0, msg: "",
     *   executionId, executionState: "SUCCESS", data: <outputs>}}; still running is
     *   {@code {status: 0, msg: "", executionId, executionState: "RUNNING",
     *   data: {}}}; failure is {@code {status: 2, msg: <error>, msgTimeout: 10000,
     *   executionId, executionState: "FAILED", data: {}}}.</li>
     * </ul>
     */
    public enum ResponseBody {
        KESTRA,
        AMIS
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
    @PluginProperty
    @Schema(
        title = "Response mode. Required — must be explicitly declared (SYNC or ASYNC).",
        description = "ASYNC: POST returns 202 + executionId immediately, client polls the status endpoint. SYNC: POST waits for a terminal state (bounded by `timeout`) and returns outputs; on timeout or PAUSED it degrades to 202 + executionId. This is a behavior-critical field: it has no default, an ApiTrigger without an explicit responseMode is rejected."
    )
    private ResponseMode responseMode;

    @NotNull
    @PluginProperty
    @Schema(
        title = "Response body shape. Required — must be explicitly declared (KESTRA or AMIS).",
        description = "KESTRA: {executionId, executionState, outputs, error} (native, keeps execution metadata for polling). AMIS: amis standard payload — success is {status: 0, msg: \"\", data: outputs} (data is the outputs map itself, no execution metadata); failure is {status: 2, msg: <error>, msgTimeout: 10000, data: {}}. Behavior-critical: no default, an ApiTrigger without an explicit responseBody is rejected."
    )
    private ResponseBody responseBody;

    @Builder.Default
    @PluginProperty
    @Schema(
        title = "SYNC wait bound.",
        description = "How long a SYNC request waits for a terminal state before degrading to 202 + executionId. Ignored in ASYNC mode."
    )
    private Duration timeout = Duration.ofSeconds(30);
}
