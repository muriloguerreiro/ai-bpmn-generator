package com.example.bpmngen.ir;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Intermediate Representation of a business process: the strict, validatable contract
 * produced by the LLM and consumed by the deterministic BPMN compiler.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProcessIr(
        String id,
        String name,
        List<Element> elements,
        List<Flow> flows
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Element(
            String id,
            ElementType type,
            String name,
            String jobType,   // serviceTask only: Zeebe task type
            String assignee   // userTask only (FEEL or static)
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Flow(
            String from,
            String to,
            String condition, // FEEL, optional (gateways in later phases)
            boolean isDefault
    ) {}
}
