package com.shadowstack.api.controllers;

import com.shadowstack.refactor.RuleCatalog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/meta")
@Tag(name = "Meta", description = "Platform capability catalog")
public class MetaController {

    @GetMapping("/languages")
    @Operation(summary = "Supported languages and modernization rules")
    public ResponseEntity<Map<String, Object>> languages() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("supported", List.of(
                Map.of(
                        "id", "java",
                        "status", "full",
                        "industryAligned", List.of("OpenRewrite", "Sonar", "JDK deprecations"),
                        "rules", RuleCatalog.javaRules().stream()
                                .map(r -> Map.of(
                                        "id", r.ruleId(),
                                        "name", r.ruleName(),
                                        "risk", r.defaultRiskTier().name()))
                                .toList()
                ),
                Map.of(
                        "id", "python",
                        "status", "full",
                        "industryAligned", List.of("lib2to3", "modernize", "futurize"),
                        "rules", List.of(
                                "py.print_stmt_to_call", "py.xrange_to_range", "py.iter_methods_to_views",
                                "py.except_comma_to_as", "py.unicode_to_str", "py.ne_operator",
                                "py.has_key_to_in", "py.raw_input_to_input", "py.long_to_int",
                                "py.raise_comma_to_call", "py.file_to_open", "py.apply_to_starcall",
                                "py.import_urllib2", "py.import_configparser", "py.import_queue",
                                "py.import_thread", "py.execfile_to_exec", "py.unicode_literal_prefix"
                        )
                ),
                Map.of(
                        "id", "cobol",
                        "status", "partial",
                        "industryAligned", List.of("enterprise COBOL→Java catalogs"),
                        "rules", List.of(
                                "cobol.fixed_to_free", "cobol.stop_run_to_goback", "cobol.goto_to_perform",
                                "cobol.alter_removed", "cobol.display_to_print", "cobol.move_to_assign",
                                "cobol.compute_to_assign", "cobol.perform_to_call", "cobol.add_to_assign",
                                "cobol.subtract_to_assign", "cobol.accept_to_input"
                        )
                )
        ));
        body.put("summary", RuleCatalog.supportSummary());
        return ResponseEntity.ok(body);
    }
}
