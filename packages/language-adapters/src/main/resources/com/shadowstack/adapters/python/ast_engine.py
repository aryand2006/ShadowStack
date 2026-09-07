#!/usr/bin/env python3
"""LibCST-based Python 2→3 modernization engine for ShadowStack.

CLI:
  python3 ast_engine.py detect <file.py>
  python3 ast_engine.py apply <file.py> <ruleId> <startLine>

detect prints a JSON array of candidates to stdout.
apply rewrites the file in place and prints {"ok":true,"ruleId":"..."} or
{"ok":false,"error":"..."}.

Py2-only syntax that cannot be parsed by LibCST under Python 3 yields no
candidates (Java regex fallback handles those files).
"""

from __future__ import annotations

import json
import sys
from dataclasses import dataclass
from typing import Any, Optional

import libcst as cst
from libcst.metadata import MetadataWrapper, PositionProvider


# ── Rule metadata ──────────────────────────────────────────────────────────

RULE_META: dict[str, dict[str, Any]] = {
    "py.xrange_to_range": {
        "ruleName": "Python 2 xrange → range",
        "confidence": 0.95,
        "risk": "LOW",
    },
    "py.iter_methods_to_views": {
        "ruleName": "Python 2 dict.iter* → dict views",
        "confidence": 0.90,
        "risk": "LOW",
    },
    "py.unicode_to_str": {
        "ruleName": "Python 2 unicode()/basestring → str",
        "confidence": 0.93,
        "risk": "LOW",
    },
    "py.has_key_to_in": {
        "ruleName": "dict.has_key(k) → (k in dict)",
        "confidence": 0.95,
        "risk": "LOW",
    },
    "py.raw_input_to_input": {
        "ruleName": "raw_input() → input()",
        "confidence": 0.93,
        "risk": "LOW",
    },
    "py.long_to_int": {
        "ruleName": "long() → int()",
        "confidence": 0.93,
        "risk": "LOW",
    },
    "py.file_to_open": {
        "ruleName": "file() → open()",
        "confidence": 0.93,
        "risk": "LOW",
    },
    "py.apply_to_starcall": {
        "ruleName": "apply(f, args) → f(*args)",
        "confidence": 0.88,
        "risk": "LOW",
    },
    "py.unichr_to_chr": {
        "ruleName": "unichr() → chr()",
        "confidence": 0.93,
        "risk": "LOW",
    },
    "py.reload_to_importlib": {
        "ruleName": "reload() → importlib.reload()",
        "confidence": 0.93,
        "risk": "LOW",
    },
    "py.intern_to_sys": {
        "ruleName": "intern() → sys.intern()",
        "confidence": 0.93,
        "risk": "LOW",
    },
    "py.standarderror_to_exception": {
        "ruleName": "StandardError → Exception",
        "confidence": 0.93,
        "risk": "LOW",
    },
    "py.next_method_to_builtin": {
        "ruleName": "x.next() → next(x)",
        "confidence": 0.86,
        "risk": "LOW",
    },
    "py.imap_to_map": {
        "ruleName": "itertools.imap → map",
        "confidence": 0.93,
        "risk": "LOW",
    },
    "py.izip_to_zip": {
        "ruleName": "itertools.izip → zip",
        "confidence": 0.93,
        "risk": "LOW",
    },
    "py.ifilter_to_filter": {
        "ruleName": "itertools.ifilter → filter",
        "confidence": 0.93,
        "risk": "LOW",
    },
    "py.reduce_to_functools": {
        "ruleName": "reduce() → functools.reduce()",
        "confidence": 0.93,
        "risk": "LOW",
    },
    "py.execfile_to_exec": {
        "ruleName": "execfile(path) → exec(open(path).read())",
        "confidence": 0.80,
        "risk": "MODERATE",
    },
    "py.unicode_literal_prefix": {
        "ruleName": "u'' prefix removal",
        "confidence": 0.92,
        "risk": "LOW",
    },
    "py.octal_literal_0o": {
        "ruleName": "0NNN → 0oNNN octal literal",
        "confidence": 0.80,
        "risk": "LOW",
    },
    "py.percent_format_to_fstring": {
        "ruleName": "% formatting → f-string/format review",
        "confidence": 0.50,
        "risk": "LOW",
    },
    "py.utf8_encoding_open": {
        "ruleName": 'open(p) → open(p, encoding="utf-8")',
        "confidence": 0.70,
        "risk": "LOW",
    },
    "py.pathlib_path": {
        "ruleName": "path string → pathlib.Path (detect)",
        "confidence": 0.40,
        "risk": "MODERATE",
    },
    "py.except_comma_to_as": {
        "ruleName": "Python 2 `except E, e:` → `except E as e:`",
        "confidence": 0.95,
        "risk": "LOW",
    },
    "py.raise_comma_to_call": {
        "ruleName": "raise E, V → raise E(V)",
        "confidence": 0.90,
        "risk": "LOW",
    },
    "py.print_stmt_to_call": {
        "ruleName": "Python 2 print statement → print() call",
        "confidence": 0.92,
        "risk": "LOW",
    },
    "py.ne_operator": {
        "ruleName": "Python 2 `<>` → `!=`",
        "confidence": 0.99,
        "risk": "LOW",
    },
    "py.map_none_to_zip": {
        "ruleName": "map(None, ...) → zip(...)",
        "confidence": 0.85,
        "risk": "MODERATE",
    },
    "py.filter_none_list": {
        "ruleName": "filter(None, x) → list(filter(None, x))",
        "confidence": 0.88,
        "risk": "LOW",
    },
    "py.list_dict_views_optional": {
        "ruleName": "list(d.keys()) → d.keys() (view)",
        "confidence": 0.7,
        "risk": "LOW",
    },
}

# import old → (new module path parts, asname, ruleId, ruleName)
IMPORT_RENAMES: dict[str, tuple[tuple[str, ...], Optional[str], str, str]] = {
    "urllib2": (("urllib", "request"), "urllib2", "py.import_urllib2", "urllib2 → urllib.request"),
    "ConfigParser": (("configparser",), "ConfigParser", "py.import_configparser", "ConfigParser → configparser"),
    "Queue": (("queue",), "Queue", "py.import_queue", "Queue → queue"),
    "thread": (("_thread",), "thread", "py.import_thread", "thread → _thread"),
    "cPickle": (("pickle",), "cPickle", "py.import_cpickle", "cPickle → pickle"),
    "cStringIO": (("io",), "cStringIO", "py.import_cstringio", "cStringIO → io"),
    "__builtin__": (("builtins",), "__builtin__", "py.import_builtin", "__builtin__ → builtins"),
    "htmlentitydefs": (("html", "entities"), "htmlentitydefs", "py.import_htmlentitydefs", "htmlentitydefs → html.entities"),
    "Cookie": (("http", "cookies"), "Cookie", "py.import_cookie", "Cookie → http.cookies"),
    "SocketServer": (("socketserver",), "SocketServer", "py.import_socketserver", "SocketServer → socketserver"),
    "commands": (("subprocess",), "commands", "py.import_commands", "commands → subprocess"),
    "urlparse": (("urllib", "parse"), "urlparse", "py.import_urlparse", "urlparse → urllib.parse"),
    "httplib": (("http", "client"), "httplib", "py.import_httplib", "httplib → http.client"),
    "BaseHTTPServer": (("http", "server"), "BaseHTTPServer", "py.import_basehttpserver", "BaseHTTPServer → http.server"),
    "md5": (("hashlib",), "md5", "py.import_md5", "md5 → hashlib"),
    "sha": (("hashlib",), "sha", "py.import_sha", "sha → hashlib"),
    "sets": (("collections",), None, "py.import_sets", "sets module removed"),
    "UserDict": (("collections",), "UserDict", "py.import_userdict", "UserDict → collections"),
    "robotparser": (("urllib", "robotparser"), "robotparser", "py.import_robotparser", "robotparser → urllib.robotparser"),
    "CGIHTTPServer": (("http", "server"), "CGIHTTPServer", "py.import_cgihttpserver", "CGIHTTPServer → http.server"),
    "SimpleHTTPServer": (("http", "server"), "SimpleHTTPServer", "py.import_simplehttpserver", "SimpleHTTPServer → http.server"),
    "cookielib": (("http", "cookiejar"), "cookielib", "py.import_cookielib", "cookielib → http.cookiejar"),
    "HTMLParser": (("html", "parser"), "HTMLParser", "py.import_htmlparser", "HTMLParser → html.parser"),
    "Tkinter": (("tkinter",), "Tkinter", "py.import_tkinter", "Tkinter → tkinter"),
    "tkFileDialog": (("tkinter", "filedialog"), "tkFileDialog", "py.import_tkfiledialog", "tkFileDialog → tkinter.filedialog"),
    "anydbm": (("dbm",), "anydbm", "py.import_anydbm", "anydbm → dbm"),
    "whichdb": (("dbm",), "whichdb", "py.import_whichdb", "whichdb → dbm"),
    "dumbdbm": (("dbm", "dumb"), "dumbdbm", "py.import_dumbdbm", "dumbdbm → dbm.dumb"),
    "gdbm": (("dbm", "gnu"), "gdbm", "py.import_gdbm", "gdbm → dbm.gnu"),
    "xmlrpclib": (("xmlrpc", "client"), "xmlrpclib", "py.import_xmlrpclib", "xmlrpclib → xmlrpc.client"),
    "SimpleXMLRPCServer": (("xmlrpc", "server"), "SimpleXMLRPCServer", "py.import_simplexmlrpcserver", "SimpleXMLRPCServer → xmlrpc.server"),
    "DocXMLRPCServer": (("xmlrpc", "server"), "DocXMLRPCServer", "py.import_docxmlrpcserver", "DocXMLRPCServer → xmlrpc.server"),
    "imp": (("importlib",), "imp", "py.import_imp", "imp → importlib"),
    "_winreg": (("winreg",), "_winreg", "py.import_winreg", "_winreg → winreg"),
    "copy_reg": (("copyreg",), "copy_reg", "py.import_copy_reg", "copy_reg → copyreg"),
    "repr": (("reprlib",), "repr", "py.import_reprlib", "repr → reprlib"),
    "dummy_thread": (("_thread",), "dummy_thread", "py.import_dummy_thread", "dummy_thread → _thread"),
    "future_builtins": (("builtins",), "future_builtins", "py.import_future_builtins", "future_builtins removed"),
}

for _old, (_parts, _as, rid, rname) in IMPORT_RENAMES.items():
    RULE_META.setdefault(rid, {"ruleName": rname, "confidence": 0.9, "risk": "LOW"})

TYPES_ALIASES: dict[str, tuple[str, str, str]] = {
    "StringType": ("str", "py.types_stringtype", "types.StringType → str"),
    "UnicodeType": ("str", "py.types_unicodetype", "types.UnicodeType → str"),
    "IntType": ("int", "py.types_inttype", "types.IntType → int"),
    "LongType": ("int", "py.types_longtype", "types.LongType → int"),
    "FloatType": ("float", "py.types_floattype", "types.FloatType → float"),
    "BooleanType": ("bool", "py.types_booleantype", "types.BooleanType → bool"),
    "ListType": ("list", "py.types_listtype", "types.ListType → list"),
    "DictType": ("dict", "py.types_dicttype", "types.DictType → dict"),
    "TupleType": ("tuple", "py.types_tupletype", "types.TupleType → tuple"),
    "NoneType": ("type(None)", "py.types_nonetype", "types.NoneType → type(None)"),
}

for _attr, (_to, rid, rname) in TYPES_ALIASES.items():
    RULE_META.setdefault(rid, {"ruleName": rname, "confidence": 0.93, "risk": "LOW"})

ITER_METHODS = {
    "iteritems": "items",
    "iterkeys": "keys",
    "itervalues": "values",
}

# Bare Name → (replacement expression code, ruleId)
NAME_REPLACEMENTS: dict[str, tuple[str, str]] = {
    "xrange": ("range", "py.xrange_to_range"),
    "basestring": ("str", "py.unicode_to_str"),
    "unicode": ("str", "py.unicode_to_str"),
    "raw_input": ("input", "py.raw_input_to_input"),
    "long": ("int", "py.long_to_int"),
    "file": ("open", "py.file_to_open"),
    "unichr": ("chr", "py.unichr_to_chr"),
    "StandardError": ("Exception", "py.standarderror_to_exception"),
    "imap": ("map", "py.imap_to_map"),
    "izip": ("zip", "py.izip_to_zip"),
    "ifilter": ("filter", "py.ifilter_to_filter"),
}


@dataclass
class Finding:
    rule_id: str
    rule_name: str
    start_line: int
    end_line: int
    before_snippet: str
    after_snippet: str
    confidence: float
    risk: str


def _meta(rule_id: str) -> dict[str, Any]:
    return RULE_META.get(rule_id, {"ruleName": rule_id, "confidence": 0.8, "risk": "LOW"})


def _line_span(source: str, start_line: int, end_line: int) -> str:
    lines = source.splitlines()
    if start_line < 1 or start_line > len(lines):
        return ""
    end = min(end_line, len(lines))
    return "\n".join(lines[start_line - 1 : end])


def _dotted_name(parts: tuple[str, ...]) -> cst.BaseExpression:
    expr: cst.BaseExpression = cst.Name(parts[0])
    for part in parts[1:]:
        expr = cst.Attribute(value=expr, attr=cst.Name(part))
    return expr


def _code(node: cst.CSTNode) -> str:
    return cst.Module([]).code_for_node(node)


def _is_simple_name(expr: cst.BaseExpression, name: str) -> bool:
    return isinstance(expr, cst.Name) and expr.value == name


def _attr_chain(expr: cst.BaseExpression) -> Optional[list[str]]:
    parts: list[str] = []
    cur: cst.BaseExpression = expr
    while isinstance(cur, cst.Attribute):
        if not isinstance(cur.attr, cst.Name):
            return None
        parts.append(cur.attr.value)
        cur = cur.value
    if isinstance(cur, cst.Name):
        parts.append(cur.value)
        parts.reverse()
        return parts
    return None


def _call_has_encoding(call: cst.Call) -> bool:
    for arg in call.args:
        if arg.keyword and arg.keyword.value == "encoding":
            return True
    return False


def _simple_percent_fstring(binary: cst.BinaryOperation) -> Optional[str]:
    """Convert '"hello %s" % name' → 'f"hello {name}"' for simple %s cases only."""
    if not isinstance(binary.operator, cst.Modulo):
        return None
    left = binary.left
    right = binary.right
    if not isinstance(left, cst.SimpleString):
        return None
    raw = left.value
    if raw[:1] not in "\"'" and raw[:2] not in ("b\"", "b'", "u\"", "u'", "r\"", "r'", "f\"", "f'"):
        pass
    # Only plain / unicode / raw strings with a single %s and a Name right-hand side.
    quote = None
    body = None
    prefix = ""
    for pfx in ("ur", "ru", "u", "r", ""):
        for q in ("'", '"'):
            token = pfx + q
            if raw.startswith(token) and raw.endswith(q) and len(raw) >= len(token) + 1:
                quote = q
                prefix = pfx.replace("u", "")  # drop u
                body = raw[len(token) : -1]
                break
        if body is not None:
            break
    if body is None or quote is None:
        return None
    if body.count("%s") != 1 or "%" in body.replace("%s", ""):
        return None
    if not isinstance(right, cst.Name):
        return None
    # Avoid braces in body.
    if "{" in body or "}" in body:
        return None
    new_body = body.replace("%s", "{" + right.value + "}")
    return f"f{prefix}{quote}{new_body}{quote}"


class ModernizeTransformer(cst.CSTTransformer):
    METADATA_DEPENDENCIES = (PositionProvider,)

    def __init__(
        self,
        source: str,
        mode: str,
        target_rule: Optional[str] = None,
        target_line: Optional[int] = None,
    ) -> None:
        super().__init__()
        self.source = source
        self.mode = mode  # "detect" | "apply"
        self.target_rule = target_rule
        self.target_line = target_line
        self.findings: list[Finding] = []
        self.applied = False
        self._seen: set[tuple[str, int]] = set()
        self._attr_name_ids: set[int] = set()

    def visit_Attribute(self, node: cst.Attribute) -> Optional[bool]:
        # Mark Attribute.attr Name nodes so leave_Name skips method/field identifiers.
        if isinstance(node.attr, cst.Name):
            self._attr_name_ids.add(id(node.attr))
        return True

    def _pos(self, node: cst.CSTNode) -> tuple[int, int]:
        pos = self.get_metadata(PositionProvider, node)
        return pos.start.line, pos.end.line

    def _record_and_maybe_replace(
        self,
        original: cst.CSTNode,
        replacement: cst.CSTNode,
        rule_id: str,
        rule_name: Optional[str] = None,
        confidence: Optional[float] = None,
        risk: Optional[str] = None,
        passthrough: Optional[cst.CSTNode] = None,
    ) -> cst.CSTNode:
        start, end = self._pos(original)
        key = (rule_id, start)
        if key in self._seen and self.mode == "detect":
            return original if passthrough is None else passthrough
        meta = _meta(rule_id)
        rname = rule_name or meta["ruleName"]
        conf = confidence if confidence is not None else float(meta["confidence"])
        rsk = risk or str(meta["risk"])

        before = _line_span(self.source, start, end)
        # Build after snippet by substituting node code within the line span.
        try:
            old_code = _code(original)
            new_code = _code(replacement)
        except Exception:
            old_code = before
            new_code = before

        after = before
        if old_code and old_code in before:
            after = before.replace(old_code, new_code, 1)
        elif start == end:
            after = before.replace(old_code, new_code, 1) if old_code else before

        keep = passthrough if passthrough is not None else original

        if self.mode == "detect":
            self._seen.add(key)
            self.findings.append(
                Finding(
                    rule_id=rule_id,
                    rule_name=rname,
                    start_line=start,
                    end_line=end,
                    before_snippet=before,
                    after_snippet=after,
                    confidence=conf,
                    risk=rsk,
                )
            )
            return keep

        # apply mode — only mutate the targeted rule+line; keep child updates otherwise
        if self.target_rule == rule_id and self.target_line == start and not self.applied:
            self.applied = True
            return replacement
        return keep

    # ── Names ─────────────────────────────────────────────────────────────

    def leave_Name(self, original_node: cst.Name, updated_node: cst.Name) -> cst.BaseExpression:
        if id(original_node) in self._attr_name_ids:
            return updated_node
        val = original_node.value
        if val in NAME_REPLACEMENTS:
            new_val, rule_id = NAME_REPLACEMENTS[val]
            if "." in new_val:
                # e.g. would need Attribute — handled elsewhere
                parts = new_val.split(".")
                repl: cst.BaseExpression = _dotted_name(tuple(parts))
            else:
                repl = updated_node.with_changes(value=new_val)
            return self._record_and_maybe_replace(original_node, repl, rule_id, passthrough=updated_node)

        if val == "reload":
            repl = cst.Attribute(value=cst.Name("importlib"), attr=cst.Name("reload"))
            return self._record_and_maybe_replace(original_node, repl, "py.reload_to_importlib", passthrough=updated_node)
        if val == "intern":
            repl = cst.Attribute(value=cst.Name("sys"), attr=cst.Name("intern"))
            return self._record_and_maybe_replace(original_node, repl, "py.intern_to_sys", passthrough=updated_node)
        if val == "reduce":
            repl = cst.Attribute(value=cst.Name("functools"), attr=cst.Name("reduce"))
            return self._record_and_maybe_replace(original_node, repl, "py.reduce_to_functools", passthrough=updated_node)
        return updated_node

    # ── Attributes / methods ──────────────────────────────────────────────

    def leave_Attribute(
        self, original_node: cst.Attribute, updated_node: cst.Attribute
    ) -> cst.BaseExpression:
        if isinstance(original_node.attr, cst.Name):
            attr = original_node.attr.value
            if attr in ITER_METHODS:
                repl = updated_node.with_changes(
                    attr=cst.Name(ITER_METHODS[attr])
                )
                return self._record_and_maybe_replace(
                    original_node, repl, "py.iter_methods_to_views",
                    rule_name=f"Python 2 dict.{attr}() → dict.{ITER_METHODS[attr]}()",
                    passthrough=updated_node,
                )
            chain = _attr_chain(original_node)
            if chain and len(chain) == 2 and chain[0] == "types" and chain[1] in TYPES_ALIASES:
                to_code, rule_id, rname = TYPES_ALIASES[chain[1]]
                if to_code == "type(None)":
                    repl = cst.parse_expression("type(None)")
                else:
                    repl = cst.Name(to_code)
                return self._record_and_maybe_replace(
                    original_node, repl, rule_id, rule_name=rname,
                    passthrough=updated_node,
                )
        return updated_node

    # ── Calls ─────────────────────────────────────────────────────────────

    def leave_Call(self, original_node: cst.Call, updated_node: cst.Call) -> cst.BaseExpression:
        func = original_node.func

        # d.has_key(k) → (k in d)
        if isinstance(func, cst.Attribute) and isinstance(func.attr, cst.Name) and func.attr.value == "has_key":
            if len(original_node.args) >= 1:
                key_arg = original_node.args[0].value
                repl = cst.parse_expression(
                    f"({_code(key_arg)} in {_code(func.value)})"
                )
                return self._record_and_maybe_replace(
                    original_node, repl, "py.has_key_to_in",
                    passthrough=updated_node,
                )

        # x.next() → next(x)
        if (
            isinstance(func, cst.Attribute)
            and isinstance(func.attr, cst.Name)
            and func.attr.value == "next"
            and len(original_node.args) == 0
        ):
            repl = cst.Call(func=cst.Name("next"), args=[cst.Arg(value=func.value)])
            return self._record_and_maybe_replace(
                original_node, repl, "py.next_method_to_builtin",
                passthrough=updated_node,
            )

        # apply(f, args) → f(*args)  [two-arg form]
        if _is_simple_name(func, "apply") and len(original_node.args) >= 2:
            f_expr = original_node.args[0].value
            args_expr = original_node.args[1].value
            star = cst.Arg(value=args_expr, star="*")
            repl = cst.Call(func=f_expr, args=[star])
            return self._record_and_maybe_replace(
                original_node, repl, "py.apply_to_starcall",
                passthrough=updated_node,
            )

        # execfile(path) → exec(open(path).read())
        if _is_simple_name(func, "execfile") and len(original_node.args) >= 1:
            path_expr = original_node.args[0].value
            open_call = cst.Call(func=cst.Name("open"), args=[cst.Arg(value=path_expr)])
            read_call = cst.Call(
                func=cst.Attribute(value=open_call, attr=cst.Name("read")),
                args=[],
            )
            repl = cst.Call(func=cst.Name("exec"), args=[cst.Arg(value=read_call)])
            return self._record_and_maybe_replace(
                original_node, repl, "py.execfile_to_exec",
                passthrough=updated_node,
            )

        # map(None, a, b, ...) → zip(a, b, ...)
        if (
            _is_simple_name(func, "map")
            and len(original_node.args) >= 2
            and isinstance(original_node.args[0].value, cst.Name)
            and original_node.args[0].value.value == "None"
        ):
            repl = cst.Call(func=cst.Name("zip"), args=list(original_node.args[1:]))
            return self._record_and_maybe_replace(
                original_node, repl, "py.map_none_to_zip",
                passthrough=updated_node,
            )

        # filter(None, x) → list(filter(None, x))  (Py3 iterator → materialize)
        if (
            _is_simple_name(func, "filter")
            and len(original_node.args) >= 2
            and isinstance(original_node.args[0].value, cst.Name)
            and original_node.args[0].value.value == "None"
        ):
            pos = self.get_metadata(PositionProvider, original_node)
            line = _line_span(self.source, pos.start.line, pos.start.line)
            if "list(filter" not in line.replace(" ", ""):
                repl = cst.Call(func=cst.Name("list"), args=[cst.Arg(value=updated_node)])
                return self._record_and_maybe_replace(
                    original_node, repl, "py.filter_none_list",
                    passthrough=updated_node,
                )

        # list(d.keys()/values()/items()) → d.keys()/… (optional view drop)
        if (
            _is_simple_name(func, "list")
            and len(original_node.args) == 1
            and isinstance(original_node.args[0].value, cst.Call)
        ):
            inner = original_node.args[0].value
            if (
                isinstance(inner.func, cst.Attribute)
                and isinstance(inner.func.attr, cst.Name)
                and inner.func.attr.value in ("keys", "values", "items")
                and len(inner.args) == 0
            ):
                return self._record_and_maybe_replace(
                    original_node, inner, "py.list_dict_views_optional",
                    passthrough=updated_node,
                )

        # open(p) without encoding → detect encoding="utf-8"
        if _is_simple_name(func, "open") and not _call_has_encoding(original_node):
            if len(original_node.args) >= 1:
                # Only flag classic one-arg / path-only forms (no mode that is clearly binary)
                mode_is_binary = False
                if len(original_node.args) >= 2 and original_node.args[1].keyword is None:
                    mode_node = original_node.args[1].value
                    if isinstance(mode_node, cst.SimpleString) and "b" in mode_node.value:
                        mode_is_binary = True
                if not mode_is_binary:
                    new_args = list(updated_node.args) + [
                        cst.Arg(
                            keyword=cst.Name("encoding"),
                            value=cst.SimpleString('"utf-8"'),
                            equal=cst.AssignEqual(
                                whitespace_before=cst.SimpleWhitespace(""),
                                whitespace_after=cst.SimpleWhitespace(""),
                            ),
                        )
                    ]
                    repl = updated_node.with_changes(args=new_args)
                    return self._record_and_maybe_replace(
                        original_node, repl, "py.utf8_encoding_open",
                        passthrough=updated_node,
                    )

        return updated_node

    # ── Imports ───────────────────────────────────────────────────────────

    def leave_Import(self, original_node: cst.Import, updated_node: cst.Import) -> cst.Import:
        new_names: list[cst.ImportAlias] = []
        changed = False
        matched_rule: Optional[tuple[str, str]] = None
        for alias in original_node.names:
            name_parts = _attr_chain(alias.name)
            if (
                name_parts
                and len(name_parts) == 1
                and name_parts[0] in IMPORT_RENAMES
                and alias.asname is None
            ):
                parts, asname, rule_id, rname = IMPORT_RENAMES[name_parts[0]]
                new_name = _dotted_name(parts)
                if asname:
                    new_alias = cst.ImportAlias(
                        name=new_name,
                        asname=cst.AsName(name=cst.Name(asname)),
                    )
                else:
                    # sets → collections (with comment handled in snippet via special case)
                    new_alias = cst.ImportAlias(name=new_name)
                # For detect/apply of a single-alias import, replace whole Import.
                if len(original_node.names) == 1:
                    repl = updated_node.with_changes(names=[new_alias])
                    # Special comment for sets
                    if name_parts[0] == "sets":
                        # Keep structural rewrite; snippet after adds comment via line replace
                        result = self._record_and_maybe_replace(
                            original_node, repl, rule_id, rule_name=rname,
                            passthrough=updated_node,
                        )
                        # Patch after_snippet to include Java-compatible comment form
                        if self.mode == "detect" and self.findings:
                            f = self.findings[-1]
                            if f.rule_id == rule_id:
                                f.after_snippet = f.after_snippet.replace(
                                    "import collections",
                                    "import collections  # was sets; use builtin set()",
                                    1,
                                )
                        return result  # type: ignore[return-value]
                    return self._record_and_maybe_replace(  # type: ignore[return-value]
                        original_node, repl, rule_id, rule_name=rname,
                        passthrough=updated_node,
                    )
                new_names.append(new_alias)
                changed = True
                matched_rule = (rule_id, rname)
            else:
                new_names.append(alias)
        if changed and matched_rule and len(new_names) == len(original_node.names):
            repl = updated_node.with_changes(names=new_names)
            return self._record_and_maybe_replace(  # type: ignore[return-value]
                original_node, repl, matched_rule[0], rule_name=matched_rule[1],
                passthrough=updated_node,
            )
        return updated_node

    def leave_ImportFrom(
        self, original_node: cst.ImportFrom, updated_node: cst.ImportFrom
    ) -> cst.ImportFrom:
        # from itertools import imap, izip, ifilter — names handled via leave_Name on aliases
        # Also handle `from X import Y` where X is a renamed module — skip for focus.
        if original_node.module is None or not isinstance(original_node.names, list):
            return updated_node
        mod_parts = _attr_chain(original_node.module)
        if not mod_parts:
            return updated_node
        # from itertools import imap → map etc. already via Name on ImportAlias
        return updated_node

    # ── Strings / formatting ──────────────────────────────────────────────

    def leave_SimpleString(
        self, original_node: cst.SimpleString, updated_node: cst.SimpleString
    ) -> cst.BaseExpression:
        raw = original_node.value
        # u"..." / u'...' prefix removal (also ur/ru)
        for pfx in ("ur", "ru", "u", "U"):
            if raw.startswith(pfx + '"') or raw.startswith(pfx + "'"):
                new_val = raw[len(pfx) :]
                # Preserve r if present in ur/ru
                if pfx in ("ur", "ru"):
                    new_val = "r" + raw[len(pfx) :]
                repl = updated_node.with_changes(value=new_val)
                return self._record_and_maybe_replace(
                    original_node, repl, "py.unicode_literal_prefix",
                    passthrough=updated_node,
                )
        return updated_node

    def leave_BinaryOperation(
        self, original_node: cst.BinaryOperation, updated_node: cst.BinaryOperation
    ) -> cst.BaseExpression:
        fscode = _simple_percent_fstring(original_node)
        if fscode is not None:
            repl = cst.parse_expression(fscode)
            # detect-only preferred for risky cases; still allow apply
            return self._record_and_maybe_replace(
                original_node, repl, "py.percent_format_to_fstring",
                passthrough=updated_node,
            )
        return updated_node

    # ── Integer octal (if LibCST exposes legacy form — rare under py3) ────

    def leave_Integer(
        self, original_node: cst.Integer, updated_node: cst.Integer
    ) -> cst.BaseExpression:
        raw = original_node.value
        # Legacy 0755 style would not parse in py3; handle 0o already modern.
        # If somehow we see 0[0-7]+ without 0o/0x/0b:
        if (
            len(raw) >= 2
            and raw[0] == "0"
            and raw[1].isdigit()
            and not raw.startswith(("0o", "0O", "0x", "0X", "0b", "0B"))
            and all(c in "01234567" for c in raw[1:])
        ):
            repl = updated_node.with_changes(value="0o" + raw[1:])
            return self._record_and_maybe_replace(
                original_node, repl, "py.octal_literal_0o",
                passthrough=updated_node,
            )
        return updated_node

    # ── Optional pathlib detect on open/path-like string constants ────────

    def leave_Assign(
        self, original_node: cst.Assign, updated_node: cst.Assign
    ) -> cst.Assign:
        # Detect: path = "foo/bar.py" style → suggest Path (detect-only soft)
        if len(original_node.targets) == 1:
            tgt = original_node.targets[0].target
            if isinstance(tgt, cst.Name) and tgt.value in {"path", "filepath", "file_path"}:
                val = original_node.value
                if isinstance(val, cst.SimpleString):
                    repl_expr = cst.parse_expression(f"Path({val.value})")
                    repl = updated_node.with_changes(value=repl_expr)
                    return self._record_and_maybe_replace(  # type: ignore[return-value]
                        original_node, repl, "py.pathlib_path",
                        passthrough=updated_node,
                    )
        return updated_node


def _parse(source: str) -> Optional[cst.Module]:
    try:
        return cst.parse_module(source)
    except Exception:
        return None


def detect(path: str) -> list[dict[str, Any]]:
    with open(path, "r", encoding="utf-8") as f:
        source = f.read()
    module = _parse(source)
    if module is None:
        return []
    wrapper = MetadataWrapper(module)
    transformer = ModernizeTransformer(source, mode="detect")
    wrapper.visit(transformer)
    out = []
    for finding in transformer.findings:
        out.append(
            {
                "ruleId": finding.rule_id,
                "ruleName": finding.rule_name,
                "startLine": finding.start_line,
                "endLine": finding.end_line,
                "beforeSnippet": finding.before_snippet,
                "afterSnippet": finding.after_snippet,
                "confidence": finding.confidence,
                "risk": finding.risk,
            }
        )
    return out


def apply_rule(path: str, rule_id: str, start_line: int) -> dict[str, Any]:
    try:
        with open(path, "r", encoding="utf-8") as f:
            source = f.read()
    except OSError as e:
        return {"ok": False, "error": str(e)}

    module = _parse(source)
    if module is None:
        return {"ok": False, "error": "parse_error: file not parseable by LibCST under Python 3"}

    wrapper = MetadataWrapper(module)
    transformer = ModernizeTransformer(
        source, mode="apply", target_rule=rule_id, target_line=start_line
    )
    try:
        new_module = wrapper.visit(transformer)
    except Exception as e:
        return {"ok": False, "error": f"transform_error: {e}"}

    if not transformer.applied:
        return {
            "ok": False,
            "error": f"no_match: rule {rule_id} at line {start_line}",
        }

    new_source = new_module.code
    # Special-case sets import comment to match Java regex form
    if rule_id == "py.import_sets":
        lines = new_source.splitlines(keepends=True)
        if 0 < start_line <= len(lines):
            line = lines[start_line - 1]
            if "import collections" in line and "was sets" not in line:
                lines[start_line - 1] = line.replace(
                    "import collections",
                    "import collections  # was sets; use builtin set()",
                    1,
                )
                new_source = "".join(lines)

    try:
        with open(path, "w", encoding="utf-8") as f:
            f.write(new_source)
    except OSError as e:
        return {"ok": False, "error": str(e)}

    return {"ok": True, "ruleId": rule_id}


def main(argv: list[str]) -> int:
    if len(argv) < 3:
        print(
            "Usage: ast_engine.py detect <file.py> | apply <file.py> <ruleId> <startLine>",
            file=sys.stderr,
        )
        return 2
    cmd = argv[1]
    if cmd == "detect":
        path = argv[2]
        print(json.dumps(detect(path), ensure_ascii=False))
        return 0
    if cmd == "apply":
        if len(argv) < 5:
            print(json.dumps({"ok": False, "error": "usage: apply <file> <ruleId> <startLine>"}))
            return 2
        path, rule_id, start_s = argv[2], argv[3], argv[4]
        try:
            start_line = int(start_s)
        except ValueError:
            print(json.dumps({"ok": False, "error": f"invalid startLine: {start_s}"}))
            return 2
        print(json.dumps(apply_rule(path, rule_id, start_line), ensure_ascii=False))
        return 0
    print(json.dumps({"ok": False, "error": f"unknown command: {cmd}"}))
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
