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
                                "py.apply_to_starcall",
                                "py.backtick_to_repr",
                                "py.except_comma_to_as",
                                "py.execfile_to_exec",
                                "py.file_to_open",
                                "py.has_key_to_in",
                                "py.import_builtin",
                                "py.import_configparser",
                                "py.import_cookie",
                                "py.import_cpickle",
                                "py.import_cstringio",
                                "py.import_htmlentitydefs",
                                "py.import_queue",
                                "py.import_socketserver",
                                "py.import_thread",
                                "py.import_urllib2",
                                "py.intern_to_sys",
                                "py.iter_methods_to_views",
                                "py.long_to_int",
                                "py.ne_operator",
                                "py.next_method_to_builtin",
                                "py.print_stmt_to_call",
                                "py.raise_comma_to_call",
                                "py.raw_input_to_input",
                                "py.reload_to_importlib",
                                "py.standarderror_to_exception",
                                "py.unichr_to_chr",
                                "py.unicode_literal_prefix",
                                "py.unicode_to_str",
                                "py.xrange_to_range"
                        )
                ),
                Map.of(
                        "id", "cobol",
                        "status", "partial",
                        "industryAligned", List.of("enterprise COBOL→Java catalogs"),
                        "rules", List.of(
                                "cobol.accept_to_input",
                                "cobol.add_to_assign",
                                "cobol.alter_removed",
                                "cobol.compute_to_assign",
                                "cobol.display_to_print",
                                "cobol.divide_to_assign",
                                "cobol.exit_program_to_return",
                                "cobol.fixed_to_free",
                                "cobol.goto_to_perform",
                                "cobol.initialize_to_clear",
                                "cobol.move_to_assign",
                                "cobol.multiply_to_assign",
                                "cobol.perform_to_call",
                                "cobol.set_to_true",
                                "cobol.stop_run_to_goback",
                                "cobol.string_to_concat",
                                "cobol.subtract_to_assign"
                        )
                )
        ));
        body.put("summary", RuleCatalog.supportSummary());
        return ResponseEntity.ok(body);
    }
}
