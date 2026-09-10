package io.kestra.plugin.core.output;

import java.util.Map;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;

import static io.kestra.core.serializers.JacksonMapper.MAP_TYPE_REFERENCE;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Emit custom values from a task.",
    description = """
        Renders the provided map and returns it under `outputs.<taskId>.values`. Accepts strings, numbers, arrays, or JSON objects; templated entries are rendered with the current context.

        Use to surface intermediate data for downstream tasks or inspection in the Outputs tab."""
)
@Plugin(
    examples = {
        @Example(
            full = true,
            code = """
                id: outputs_flow
                namespace: company.team

                tasks:
                  - id: output_values
                    type: io.kestra.plugin.core.output.OutputValues
                    values:
                      taskrun_data: "{{ task.id }} > {{ taskrun.startDate }}"
                      execution_data: "{{ flow.id }} > {{ execution.startDate }}"
                      number_value: 42
                      array_value: ["{{ task.id }}", "{{ flow.id }}", "static value"]
                      nested_object:
                        key1: "value1"
                        key2: "{{ execution.id }}"

                  - id: log_values
                    type: io.kestra.plugin.core.log.Log
                    message: |
                      Got the following outputs from the previous task:
                      {{ outputs.output_values.values.taskrun_data }}
                      {{ outputs.output_values.values.execution_data }}
                      {{ outputs.output_values.values.number_value }}
                      {{ outputs.output_values.values.array_value[1] }}
                      {{ outputs.output_values.values.nested_object.key2 }}
                """
        )
    }
)
public class OutputValues extends Task implements RunnableTask<OutputValues.Output> {
    @Schema(
        title = "The templated strings to render",
        description = "These values can be strings, numbers, arrays, or objects. Templated strings (enclosed in {{ }}) will be rendered using the current context.",
        anyOf = { Map.class, String.class }
    )
    @PluginProperty(dynamic = true)
    private Object values;

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public OutputValues.Output run(RunContext runContext) throws Exception {
        return Output.builder()
            .values(renderValues(runContext))
            .build();
    }

    /**
     * Renders {@code values} and converts it to a map, mirroring {@link io.kestra.core.models.property.Property#asMap}:
     * a Pebble expression (e.g. {@code '{{ read(''nsfile:///...'') }}'}) is rendered first and the resulting JSON
     * string is parsed back into a map; a YAML map literal is rendered entry by entry. Accepting a plain string
     * value lets a whole payload (e.g. an Amis page schema stored in a namespace file) be emitted as a single file
     * reference without restating its structure, while the yaml-schema validator accepts it via the anyOf above.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Map<String, Object> renderValues(RunContext runContext) throws Exception {
        if (values instanceof String str) {
            Object rendered = runContext.render(str);
            if (rendered instanceof Map<?, ?> m) {
                return (Map<String, Object>) m;
            }
            if (rendered instanceof String s) {
                try {
                    return JacksonMapper.ofJson().readValue(s, MAP_TYPE_REFERENCE);
                } catch (Exception e) {
                    // Not JSON — keep the rendered string as a single value (same fallback as asMap failure would surface).
                    throw new IllegalVariableEvaluationException("Unable to parse `values` as a map: " + e.getMessage());
                }
            }
            throw new IllegalVariableEvaluationException("`values` must render to a map or a JSON string");
        }
        if (values instanceof Map) {
            return (Map<String, Object>) runContext.render((Map) values);
        }
        throw new IllegalVariableEvaluationException("`values` must be a map or a JSON string");
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "The generated values"
        )
        private Map<String, Object> values;
    }
}
