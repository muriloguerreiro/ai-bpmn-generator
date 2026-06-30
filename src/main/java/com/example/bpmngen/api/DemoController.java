package com.example.bpmngen.api;

import com.example.bpmngen.bpmn.IrToBpmnCompiler;
import com.example.bpmngen.ir.ElementType;
import com.example.bpmngen.ir.ProcessIr;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Phase 0 verification endpoint: compiles a hard-coded {@link ProcessIr} into BPMN and
 * returns it as a downloadable .bpmn file. Proves the IR -> BPMN pipeline end-to-end
 * before the LLM is wired in (Phase 1).
 */
@RestController
public class DemoController {

    private final IrToBpmnCompiler compiler;

    public DemoController(IrToBpmnCompiler compiler) {
        this.compiler = compiler;
    }

    @GetMapping(value = "/api/demo", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> demo() {
        ProcessIr ir = new ProcessIr(
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

        String xml = compiler.toXml(ir);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + ir.id() + ".bpmn\"")
                .contentType(MediaType.APPLICATION_XML)
                .body(xml);
    }
}
