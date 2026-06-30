package com.example.bpmngen.bpmn;

import com.example.bpmngen.ir.ElementType;
import com.example.bpmngen.ir.ProcessIr;
import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IrToBpmnCompilerTest {

    private final IrToBpmnCompiler compiler = new IrToBpmnCompiler();

    private ProcessIr linearIr() {
        return new ProcessIr(
                "invoice_approval",
                "Invoice Approval",
                List.of(
                        new ProcessIr.Element("start", ElementType.startEvent, "Invoice received", null, null),
                        new ProcessIr.Element("review", ElementType.userTask, "Review invoice", null, "=manager"),
                        new ProcessIr.Element("approve", ElementType.serviceTask, "Auto-approve", "auto-approve", null),
                        new ProcessIr.Element("done", ElementType.endEvent, "Approved", null, null)
                ),
                List.of(
                        new ProcessIr.Flow("start", "review", null, false),
                        new ProcessIr.Flow("review", "approve", null, false),
                        new ProcessIr.Flow("approve", "done", null, false)
                )
        );
    }

    @Test
    void compilesValidModelWithZeebeExtensionAndAutoLayout() {
        BpmnModelInstance model = compiler.compile(linearIr());

        // Valid per the Zeebe BPMN model (throws on invalid references/structure).
        Bpmn.validateModel(model);

        String xml = Bpmn.convertToString(model);

        // Structure
        assertThat(xml).contains("<process id=\"invoice_approval\"");
        assertThat(xml).contains("isExecutable=\"true\"");
        assertThat(xml).contains("<userTask id=\"review\"");
        assertThat(xml).contains("<serviceTask id=\"approve\"");

        // Zeebe extension (namespace URI, regardless of prefix)
        assertThat(xml).contains("http://camunda.org/schema/zeebe/1.0");
        assertThat(xml).contains("taskDefinition");
        assertThat(xml).contains("auto-approve");

        // Auto-generated diagram interchange (coordinates + waypoints)
        assertThat(xml).contains("BPMNDiagram");
        assertThat(xml).contains("dc:Bounds");
        assertThat(xml).contains("di:waypoint");
    }

    @Test
    void rejectsBranchingInMvp() {
        ProcessIr branching = new ProcessIr(
                "p", "P",
                List.of(
                        new ProcessIr.Element("start", ElementType.startEvent, null, null, null),
                        new ProcessIr.Element("a", ElementType.endEvent, null, null, null),
                        new ProcessIr.Element("b", ElementType.endEvent, null, null, null)
                ),
                List.of(
                        new ProcessIr.Flow("start", "a", null, false),
                        new ProcessIr.Flow("start", "b", null, false)
                )
        );

        assertThatThrownBy(() -> compiler.compile(branching))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("branching");
    }

    @Test
    void serviceTaskRequiresJobType() {
        ProcessIr ir = new ProcessIr(
                "p", "P",
                List.of(
                        new ProcessIr.Element("start", ElementType.startEvent, null, null, null),
                        new ProcessIr.Element("svc", ElementType.serviceTask, "no job type", null, null),
                        new ProcessIr.Element("done", ElementType.endEvent, null, null, null)
                ),
                List.of(
                        new ProcessIr.Flow("start", "svc", null, false),
                        new ProcessIr.Flow("svc", "done", null, false)
                )
        );

        assertThatThrownBy(() -> compiler.compile(ir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jobType");
    }
}
