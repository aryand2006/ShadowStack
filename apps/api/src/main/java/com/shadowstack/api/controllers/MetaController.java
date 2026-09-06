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
                        "status", "adapter",
                        "industryAligned", List.of("lib2to3", "modernize", "futurize"),
                        "rules", List.of(
                                "py.apply_to_starcall",
                                "py.backtick_to_repr",
                                "py.cmp_removed",
                                "py.except_comma_to_as",
                                "py.exec_stmt_to_call",
                                "py.execfile_to_exec",
                                "py.file_to_open",
                                "py.filter_none_list",
                                "py.has_key_to_in",
                                "py.ifilter_to_filter",
                                "py.imap_to_map",
                                "py.import_anydbm",
                                "py.import_basehttpserver",
                                "py.import_builtin",
                                "py.import_builtin_dunder",
                                "py.import_cgihttpserver",
                                "py.import_commands",
                                "py.import_configparser",
                                "py.import_cookie",
                                "py.import_cookielib",
                                "py.import_copy_reg",
                                "py.import_cpickle",
                                "py.import_cstringio",
                                "py.import_docxmlrpcserver",
                                "py.import_dumbdbm",
                                "py.import_dummy_thread",
                                "py.import_future_builtins",
                                "py.import_gdbm",
                                "py.import_htmlentitydefs",
                                "py.import_htmlparser",
                                "py.import_httplib",
                                "py.import_imp",
                                "py.import_md5",
                                "py.import_queue",
                                "py.import_reprlib",
                                "py.import_robotparser",
                                "py.import_sets",
                                "py.import_sha",
                                "py.import_simplehttpserver",
                                "py.import_simplexmlrpcserver",
                                "py.import_socketserver",
                                "py.import_socketserver_alias",
                                "py.import_thread",
                                "py.import_tkfiledialog",
                                "py.import_tkinter",
                                "py.import_urllib2",
                                "py.import_urlparse",
                                "py.import_userdict",
                                "py.import_whichdb",
                                "py.import_winreg",
                                "py.import_xmlrpclib",
                                "py.intern_to_sys",
                                "py.iter_methods_to_views",
                                "py.izip_to_zip",
                                "py.list_dict_views_optional",
                                "py.long_to_int",
                                "py.map_none_to_zip",
                                "py.metaclass_attr_to_kwarg",
                                "py.ne_operator",
                                "py.next_method_to_builtin",
                                "py.octal_literal_0o",
                                "py.percent_format_to_fstring",
                                "py.print_chevron_to_file",
                                "py.print_stmt_to_call",
                                "py.raise_comma_to_call",
                                "py.raw_input_to_input",
                                "py.reduce_to_functools",
                                "py.reload_to_importlib",
                                "py.standarderror_to_exception",
                                "py.types_booleantype",
                                "py.types_dicttype",
                                "py.types_floattype",
                                "py.types_inttype",
                                "py.types_listtype",
                                "py.types_longtype",
                                "py.types_nonetype",
                                "py.types_stringtype",
                                "py.types_tupletype",
                                "py.types_unicodetype",
                                "py.unichr_to_chr",
                                "py.unicode_literal_prefix",
                                "py.unicode_to_str",
                                "py.xrange_to_range"
                        )
                ),
                Map.of(
                        "id", "cobol",
                        "status", "adapter",
                        "industryAligned", List.of("COBOL-preserving modernization (fixed→free, GOBACK, PERFORM)"),
                        "rules", List.of(
                                "cobol.accept_to_input",
                                "cobol.add_to_assign",
                                "cobol.allocate_to_new",
                                "cobol.alter_removed",
                                "cobol.call_to_invoke",
                                "cobol.cancel_to_unload",
                                "cobol.close_to_close",
                                "cobol.compute_to_assign",
                                "cobol.continue_to_empty",
                                "cobol.delete_to_delete",
                                "cobol.display_to_print",
                                "cobol.divide_to_assign",
                                "cobol.evaluate_to_switch",
                                "cobol.exit_paragraph_to_return",
                                "cobol.exit_program_to_return",
                                "cobol.exit_section_to_return",
                                "cobol.fixed_to_free",
                                "cobol.free_to_null",
                                "cobol.goto_depending_to_switch",
                                "cobol.goto_to_perform",
                                "cobol.initialize_to_clear",
                                "cobol.inspect_replacing",
                                "cobol.inspect_tallying",
                                "cobol.merge_to_merge",
                                "cobol.move_corresponding",
                                "cobol.move_to_assign",
                                "cobol.multiply_to_assign",
                                "cobol.open_to_stream",
                                "cobol.perform_times_to_for",
                                "cobol.perform_to_call",
                                "cobol.perform_until_to_while",
                                "cobol.perform_varying_to_for",
                                "cobol.read_to_read",
                                "cobol.release_to_emit",
                                "cobol.return_to_poll",
                                "cobol.rewrite_to_update",
                                "cobol.search_to_lookup",
                                "cobol.set_address_to_pointer",
                                "cobol.set_to_true",
                                "cobol.sort_to_sort",
                                "cobol.start_to_position",
                                "cobol.stop_run_to_goback",
                                "cobol.string_to_concat",
                                "cobol.subtract_to_assign",
                                "cobol.unstring_to_split",
                                "cobol.write_to_write"
                        )
                ),
                Map.of(
                        "id", "javascript",
                        "status", "adapter",
                        "industryAligned", List.of("ESLint", "TypeScript ES5→modern", "CommonJS→ESM"),
                        "rules", List.of(
                                "js.!=_to_!==",
                                "js.==_to_===",
                                "js.arguments_to_rest",
                                "js.bind_to_arrow",
                                "js.callback_err_first",
                                "js.charat0_to_at",
                                "js.dirname_to_importmeta",
                                "js.escape_to_encodeuri",
                                "js.exports_dot_to_export",
                                "js.filename_to_importmeta",
                                "js.indexof_to_includes",
                                "js.indexof_zero_to_startswith",
                                "js.module_exports_to_export",
                                "js.object_assign_to_spread",
                                "js.promise_constructor_to_async",
                                "js.require_to_import",
                                "js.string_concat_plus",
                                "js.substr_to_substring",
                                "js.unescape_to_decodeuri",
                                "js.var_to_let"
                        )
                ),
                Map.of(
                        "id", "csharp",
                        "status", "adapter",
                        "industryAligned", List.of(".NET Framework→modern", "CA/FxCop classics"),
                        "rules", List.of(
                                "cs.arraylist_to_list",
                                "cs.asynchronous_begin_end",
                                "cs.binaryformatter_removed",
                                "cs.concurrentdict_tryadd",
                                "cs.configurationmanager_to_iconfiguration",
                                "cs.hashtable_to_dictionary",
                                "cs.httprequest_to_httpclient",
                                "cs.nameof_for_literals",
                                "cs.namevaluecollection_to_dict",
                                "cs.nullable_enable",
                                "cs.principalpermission_removed",
                                "cs.readonlycollection_to_ilist",
                                "cs.remoting_removed",
                                "cs.string_format_to_interpolation",
                                "cs.string_isempty",
                                "cs.stringbuilder_appendformat",
                                "cs.threadabort_removed",
                                "cs.webclient_to_httpclient",
                                "cs.webrequest_to_httpclient"
                        )
                )
        ));
        body.put("summary", RuleCatalog.supportSummary());
        return ResponseEntity.ok(body);
    }
}
