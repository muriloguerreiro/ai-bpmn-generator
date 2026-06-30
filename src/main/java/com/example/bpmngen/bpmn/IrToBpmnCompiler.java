package com.example.bpmngen.bpmn;

import com.example.bpmngen.ir.ProcessIr;
import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.camunda.zeebe.model.bpmn.builder.AbstractFlowNodeBuilder;
import io.camunda.zeebe.model.bpmn.builder.ProcessBuilder;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deterministically compiles a {@link ProcessIr} into a Camunda 8 (Zeebe) BPMN model.
 *
 * <p>Uses the {@code zeebe-bpmn-model} fluent builder, which attaches Zeebe extension
 * elements and auto-generates the diagram interchange (DI) coordinates.
 *
 * <p>MVP scope: linear processes (one outgoing flow per node). Gateways/branching are a
 * later phase; encountering them here raises a clear error rather than producing junk.
 */
@Component
public class IrToBpmnCompiler {

    public BpmnModelInstance compile(ProcessIr ir) {
        if (ir == null || ir.id() == null || ir.id().isBlank()) {
            throw new IllegalArgumentException("IR must have a non-blank process id");
        }
        if (ir.elements() == null || ir.elements().isEmpty()) {
            throw new IllegalArgumentException("IR must contain at least one element");
        }

        Map<String, ProcessIr.Element> byId = new LinkedHashMap<>();
        for (ProcessIr.Element e : ir.elements()) {
            if (e.id() == null || e.id().isBlank()) {
                throw new IllegalArgumentException("Every element must have a non-blank id");
            }
            if (byId.put(e.id(), e) != null) {
                throw new IllegalArgumentException("Duplicate element id: " + e.id());
            }
        }

        Map<String, String> nextOf = new HashMap<>();
        if (ir.flows() != null) {
            for (ProcessIr.Flow f : ir.flows()) {
                if (nextOf.put(f.from(), f.to()) != null) {
                    throw new IllegalStateException(
                            "Node '" + f.from() + "' has multiple outgoing flows; "
                                    + "branching (gateways) is not supported in the MVP");
                }
            }
        }

        ProcessIr.Element start = ir.elements().stream()
                .filter(e -> e.type() == com.example.bpmngen.ir.ElementType.startEvent)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("IR must contain a startEvent"));

        ProcessBuilder process = Bpmn.createExecutableProcess(ir.id());
        if (ir.name() != null && !ir.name().isBlank()) {
            process.name(ir.name());
        }

        AbstractFlowNodeBuilder<?, ?> b = applyName(process.startEvent(start.id()), start.name());

        String cursor = nextOf.get(start.id());
        int guard = 0;
        while (cursor != null) {
            if (++guard > byId.size() + 1) {
                throw new IllegalStateException("Cycle detected in flows starting from " + start.id());
            }
            ProcessIr.Element el = byId.get(cursor);
            if (el == null) {
                throw new IllegalArgumentException("Flow targets unknown element id: " + cursor);
            }
            b = appendNode(b, el);
            cursor = nextOf.get(cursor);
        }

        return b.done();
    }

    public String toXml(ProcessIr ir) {
        return Bpmn.convertToString(compile(ir));
    }

    private AbstractFlowNodeBuilder<?, ?> appendNode(AbstractFlowNodeBuilder<?, ?> b, ProcessIr.Element el) {
        return switch (el.type()) {
            case userTask -> applyName(b.userTask(el.id()), el.name());
            case serviceTask -> {
                var st = b.serviceTask(el.id());
                if (el.jobType() == null || el.jobType().isBlank()) {
                    throw new IllegalArgumentException("serviceTask '" + el.id() + "' requires a jobType");
                }
                st.zeebeJobType(el.jobType());
                yield applyName(st, el.name());
            }
            case endEvent -> applyName(b.endEvent(el.id()), el.name());
            case startEvent ->
                    throw new IllegalArgumentException("Unexpected second startEvent: " + el.id());
        };
    }

    private AbstractFlowNodeBuilder<?, ?> applyName(AbstractFlowNodeBuilder<?, ?> b, String name) {
        if (name != null && !name.isBlank()) {
            b.name(name);
        }
        return b;
    }
}
