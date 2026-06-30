package com.example.bpmngen.ir;

/**
 * The Camunda-8 BPMN element subset the generator supports.
 * Kept intentionally small for the MVP; grows in later phases (gateways, events, ...).
 */
public enum ElementType {
    startEvent,
    endEvent,
    userTask,
    serviceTask
}
