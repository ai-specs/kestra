package io.kestra.webserver.services;

import io.kestra.core.async.AsyncOperationProcessedEvent;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.flows.FlowInterface;
import io.kestra.core.models.Label;
import io.kestra.core.runners.ProcessedFlow;
import io.kestra.core.models.flows.State;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.models.executions.ExecutionTrigger;
import io.kestra.core.repositories.ExecutionRepositoryInterface;
import io.kestra.core.runners.FlowInputOutput;
import io.kestra.core.runners.FlowMetaStoreInterface;
import io.kestra.core.runners.FlowMetaStores;
import io.kestra.core.services.ConditionService;
import io.kestra.core.services.LabelService;
import io.kestra.core.services.WebhookService;
import io.kestra.core.utils.IdUtils;
import io.micronaut.http.sse.Event;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Execution bridge for dsh Apps (design docs/dsh-apps-amis.md §5.3/§5.7): builds an
 * {@link Execution} for the declaring flow from a request body, reusing
 * {@link WebhookService}'s type-agnostic methods (start/follow/outputs) so the
 * trigger classes stay a thin declarative plugin with zero execution logic.
 */
@Singleton
@Slf4j
public class AppsService {

    @Inject
    private ConditionService conditionService;

    @Inject
    private FlowInputOutput flowInputOutput;

    @Inject
    private FlowMetaStoreInterface flowMetaStore;

    @Inject
    private WebhookService webhookService;

    @Inject
    private ExecutionRepositoryInterface executionRepository;

    /**
     * Build a prepared execution for the app API call, or empty when the trigger
     * conditions are not met. Mirrors {@link WebhookService#newExecution} minus the
     * webhook-specific request/file handling: the inputs come from the request body.
     *
     * @param flow       the flow declaring the ApiTrigger (raw, from the route registry)
     * @param trigger    the ApiTrigger instance
     * @param bodyInputs request body mapped to flow inputs (already flattened)
     * @return the prepared execution, or empty if the trigger conditions are not met
     */
    public Optional<Execution> createExecution(Flow flow, AbstractTrigger trigger, Map<String, Object> bodyInputs) {
        ProcessedFlow processedFlow = FlowMetaStores.findForRuntimeOrRaw(flowMetaStore, flow);
        FlowInterface resolvedFlow = processedFlow.flow();

        Execution execution = Execution.builder()
            .id(IdUtils.create())
            .tenantId(flow.getTenantId())
            .namespace(flow.getNamespace())
            .flowId(flow.getId())
            .flowRevision(flow.getRevision())
            .inputs(new HashMap<>())
            .variables(resolvedFlow.getVariables())
            .state(new State())
            .trigger(ExecutionTrigger.of(trigger, Map.of()))
            .build();

        var runContext = webhookService.runContext(flow, execution);

        List<Label> contributed = new ArrayList<>();
        contributed.add(new Label(Label.FROM, Label.FromLabel.TRIGGER.value));
        contributed.addAll(LabelService.fromTrigger(runContext, trigger, Map.of()));

        execution = execution.withLabels(
            LabelService.forExecution(
                resolvedFlow,
                LabelService.withoutPinned(contributed, processedFlow.pinnedLabelKeys()),
                execution.getId()
            )
        );

        if (!conditionService.isValid(trigger, flow, runContext)) {
            return Optional.empty();
        }

        if (bodyInputs != null && !bodyInputs.isEmpty()) {
            try {
                Map<String, Object> rendered = runContext.render(bodyInputs);
                rendered = flowInputOutput.readExecutionInputs(flow, execution, rendered);
                execution = execution.withInputs(rendered);
            } catch (Exception e) {
                log.warn("Unable to render app inputs", e);
                throw new IllegalArgumentException("Unable to render app inputs: " + e.getMessage(), e);
            }
        }

        return Optional.of(execution);
    }

    public Mono<AsyncOperationProcessedEvent> startExecution(Execution execution) {
        return webhookService.startExecution(execution);
    }

    public Flux<Event<Execution>> followExecution(Execution execution, Flow flow) {
        return webhookService.followExecution(execution, flow);
    }

    /**
     * Look up an execution scoped to the app's flow: (tenant, namespace, flowId, id).
     * Refuses executions that do not belong to the matched flow, so the status
     * endpoint cannot act as an arbitrary execution-id oracle.
     */
    public Optional<Execution> findScopedExecution(String tenant, String namespace, String flowId, String executionId) {
        Optional<Execution> maybe = executionRepository.findByIdWithoutAcl(tenant, executionId);
        if (maybe.isEmpty()) {
            return Optional.empty();
        }
        Execution e = maybe.get();
        if (!namespace.equals(e.getNamespace()) || !flowId.equals(e.getFlowId())) {
            return Optional.empty();
        }
        return maybe;
    }
}
