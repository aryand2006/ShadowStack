#!/usr/bin/env node
/**
 * Acorn-based JavaScript modernization engine for ShadowStack.
 *
 * CLI:
 *   node ast_engine.mjs detect <file.js>
 *   node ast_engine.mjs apply <file.js> <ruleId> <startLine>
 *
 * detect → JSON array of {ruleId, ruleName, startLine, endLine,
 *   beforeSnippet, afterSnippet, confidence, risk}
 * apply → rewrite file, print {"ok":true,...} or {"ok":false,"error":"..."}
 *
 * Prefers surgical range replacement via node.start/end offsets so unrelated
 * formatting is preserved. astring is available for whole-Program codegen.
 */

import * as acorn from 'acorn';
import { generate } from 'astring';
import fs from 'node:fs';
import path from 'node:path';

const PARSE_OPTS = { ecmaVersion: 2022, sourceType: 'script', locations: true };

const RULE_META = {
  'js.var_to_let': { ruleName: 'var → let', confidence: 0.9, risk: 'LOW' },
  'js.prefer_const': {
    ruleName: 'prefer const when never reassigned',
    confidence: 0.88,
    risk: 'LOW',
  },
  'js.==_to_===': {
    ruleName: 'Loose equality → strict equality',
    confidence: 0.82,
    risk: 'MODERATE',
  },
  'js.!=_to_!==': {
    ruleName: 'Loose inequality → strict inequality',
    confidence: 0.82,
    risk: 'MODERATE',
  },
  'js.substr_to_substring': {
    ruleName: 'substr() → substring()',
    confidence: 0.78,
    risk: 'MODERATE',
  },
  'js.indexof_to_includes': {
    ruleName: 'indexOf membership check → includes',
    confidence: 0.92,
    risk: 'LOW',
  },
  'js.indexof_zero_to_startswith': {
    ruleName: 'indexOf(x) === 0 → startsWith(x)',
    confidence: 0.88,
    risk: 'LOW',
  },
  'js.charat0_to_at': { ruleName: 'charAt(0) → at(0)', confidence: 0.7, risk: 'LOW' },
  'js.object_assign_to_spread': {
    ruleName: 'Object.assign({}, x) → ({...x})',
    confidence: 0.8,
    risk: 'LOW',
  },
  'js.escape_to_encodeuri': {
    ruleName: 'escape() → encodeURI()',
    confidence: 0.76,
    risk: 'MODERATE',
  },
  'js.unescape_to_decodeuri': {
    ruleName: 'unescape() → decodeURI()',
    confidence: 0.76,
    risk: 'MODERATE',
  },
  'js.string_concat_plus': {
    ruleName: 'String concatenation → template literal',
    confidence: 0.96,
    risk: 'LOW',
  },
  'js.require_to_import': {
    ruleName: 'CommonJS require → ES module import',
    confidence: 0.88,
    risk: 'MODERATE',
  },
  'js.module_exports_to_export': {
    ruleName: 'module.exports → export default',
    confidence: 0.84,
    risk: 'MODERATE',
  },
  'js.exports_dot_to_export': {
    ruleName: 'Named CommonJS export → ES module export',
    confidence: 0.82,
    risk: 'MODERATE',
  },
  'js.nullable_chaining': {
    ruleName: 'a && a.b → a?.b (detect)',
    confidence: 0.55,
    risk: 'MODERATE',
  },
  'js.optional_catch_binding': {
    ruleName: 'unused catch binding → optional catch binding',
    confidence: 0.9,
    risk: 'LOW',
  },
  'js.dirname_to_importmeta': {
    ruleName: '__dirname → import.meta.dirname',
    confidence: 0.86,
    risk: 'MODERATE',
  },
  'js.filename_to_importmeta': {
    ruleName: '__filename → import.meta.url',
    confidence: 0.82,
    risk: 'MODERATE',
  },
};

function parseSource(source, sourceType = 'script') {
  return acorn.parse(source, { ...PARSE_OPTS, sourceType });
}

function walk(node, visitors, parent = null) {
  if (!node || typeof node.type !== 'string') return;
  const visit = visitors[node.type];
  if (visit) visit(node, parent);
  for (const key of Object.keys(node)) {
    if (key === 'loc' || key === 'range' || key === 'start' || key === 'end') continue;
    const child = node[key];
    if (Array.isArray(child)) {
      for (const c of child) {
        if (c && typeof c.type === 'string') walk(c, visitors, node);
      }
    } else if (child && typeof child.type === 'string') {
      walk(child, visitors, node);
    }
  }
}

function lineOf(source, index) {
  let line = 1;
  for (let i = 0; i < index && i < source.length; i++) {
    if (source.charAt(i) === '\n') line++;
  }
  return line;
}

function snippetForRange(source, start, end, replacement) {
  const startLine = lineOf(source, start);
  const endLine = lineOf(source, Math.max(start, end - 1));
  const beforeLines = source.split('\n');
  const afterSource = source.slice(0, start) + replacement + source.slice(end);
  const afterLines = afterSource.split('\n');
  return {
    startLine,
    endLine,
    beforeSnippet: beforeLines[startLine - 1] ?? '',
    afterSnippet: afterLines[startLine - 1] ?? '',
    replacement,
    start,
    end,
  };
}

function isIdentifier(node, name) {
  return node && node.type === 'Identifier' && (!name || node.name === name);
}

function memberName(node) {
  if (!node || node.type !== 'MemberExpression' || node.computed) return null;
  if (node.property && node.property.type === 'Identifier') return node.property.name;
  return null;
}

function isObjectAssignCall(node) {
  return (
    node.type === 'CallExpression' &&
    node.callee?.type === 'MemberExpression' &&
    !node.callee.computed &&
    isIdentifier(node.callee.object, 'Object') &&
    isIdentifier(node.callee.property, 'assign')
  );
}

function isEmptyObjectLiteral(node) {
  return node && node.type === 'ObjectExpression' && (node.properties?.length ?? 0) === 0;
}

function literalStringValue(node) {
  if (node && node.type === 'Literal' && typeof node.value === 'string') return node.value;
  return null;
}

function isSimpleStringLiteral(node) {
  return node && node.type === 'Literal' && typeof node.value === 'string';
}

function collectLValueNames(node, out) {
  if (!node) return;
  if (node.type === 'Identifier') {
    out.add(node.name);
  } else if (node.type === 'ArrayPattern') {
    for (const el of node.elements || []) collectLValueNames(el, out);
  } else if (node.type === 'ObjectPattern') {
    for (const prop of node.properties || []) {
      if (prop.type === 'Property') collectLValueNames(prop.value, out);
      else if (prop.type === 'RestElement') collectLValueNames(prop.argument, out);
    }
  } else if (node.type === 'RestElement') {
    collectLValueNames(node.argument, out);
  } else if (node.type === 'AssignmentPattern') {
    collectLValueNames(node.left, out);
  }
}

function collectAssignedNames(ast) {
  const assigned = new Set();
  walk(ast, {
    AssignmentExpression(node) {
      collectLValueNames(node.left, assigned);
    },
    UpdateExpression(node) {
      collectLValueNames(node.argument, assigned);
    },
    ForInStatement(node) {
      if (node.left?.type === 'Identifier') assigned.add(node.left.name);
    },
    ForOfStatement(node) {
      if (node.left?.type === 'Identifier') assigned.add(node.left.name);
    },
  });
  return assigned;
}

function declaratorNames(decl) {
  const set = new Set();
  collectLValueNames(decl.id, set);
  return [...set];
}

function isBindingReassigned(decl, assigned) {
  return declaratorNames(decl).some((name) => assigned.has(name));
}

function findCatchParamUses(param, body) {
  if (!param || param.type !== 'Identifier') return true;
  const name = param.name;
  let used = false;
  walk(body, {
    Identifier(node) {
      if (node.name === name) used = true;
    },
  });
  return used;
}

function makeCandidate(ruleId, snippet) {
  const meta = RULE_META[ruleId] || { ruleName: ruleId, confidence: 0.7, risk: 'MODERATE' };
  return {
    ruleId,
    ruleName: meta.ruleName,
    startLine: snippet.startLine,
    endLine: snippet.endLine,
    beforeSnippet: snippet.beforeSnippet,
    afterSnippet: snippet.afterSnippet,
    confidence: meta.confidence,
    risk: meta.risk,
    _start: snippet.start,
    _end: snippet.end,
    _replacement: snippet.replacement,
  };
}

function callArgSource(source, call) {
  const open = source.indexOf('(', call.callee.end);
  const close = call.end - 1;
  return open >= 0 ? source.slice(open + 1, close).trim() : '';
}

function detectInternal(source) {
  let ast;
  try {
    ast = parseSource(source);
  } catch {
    try {
      ast = parseSource(source, 'module');
    } catch {
      return [];
    }
  }

  const assigned = collectAssignedNames(ast);
  const candidates = [];

  walk(ast, {
    VariableDeclaration(node, parent) {
      if (node.kind === 'var') {
        const kindStart = node.start;
        const kindEnd = kindStart + 3;
        if (source.slice(kindStart, kindEnd) === 'var') {
          candidates.push(
            makeCandidate('js.var_to_let', snippetForRange(source, kindStart, kindEnd, 'let')),
          );
        }
        if (node.declarations.every((d) => !isBindingReassigned(d, assigned))) {
          candidates.push(
            makeCandidate('js.prefer_const', snippetForRange(source, kindStart, kindEnd, 'const')),
          );
        }
      } else if (node.kind === 'let') {
        if (node.declarations.every((d) => !isBindingReassigned(d, assigned))) {
          const kindStart = node.start;
          const kindEnd = kindStart + 3;
          if (source.slice(kindStart, kindEnd) === 'let') {
            candidates.push(
              makeCandidate('js.prefer_const', snippetForRange(source, kindStart, kindEnd, 'const')),
            );
          }
        }
      }

      // require → import (detect everywhere; apply only succeeds for top-level)
      for (const decl of node.declarations) {
        if (
          decl.id?.type === 'Identifier' &&
          decl.init?.type === 'CallExpression' &&
          isIdentifier(decl.init.callee, 'require') &&
          decl.init.arguments?.length === 1 &&
          isSimpleStringLiteral(decl.init.arguments[0])
        ) {
          const mod = literalStringValue(decl.init.arguments[0]);
          const quote = source[decl.init.arguments[0].start];
          const replacement = `import ${decl.id.name} from ${quote}${mod}${quote}`;
          const cand = makeCandidate(
            'js.require_to_import',
            snippetForRange(source, node.start, node.end, replacement),
          );
          cand._topLevel = !!(parent && parent.type === 'Program');
          candidates.push(cand);
        }
      }
    },

    BinaryExpression(node) {
      if (node.operator === '==') {
        const between = source.slice(node.left.end, node.right.start);
        const idx = between.indexOf('==');
        if (idx >= 0) {
          const abs = node.left.end + idx;
          if (source.slice(abs, abs + 2) === '==' && source.slice(abs, abs + 3) !== '===') {
            candidates.push(
              makeCandidate('js.==_to_===', snippetForRange(source, abs, abs + 2, '===')),
            );
          }
        }
      } else if (node.operator === '!=') {
        const between = source.slice(node.left.end, node.right.start);
        const idx = between.indexOf('!=');
        if (idx >= 0) {
          const abs = node.left.end + idx;
          if (source.slice(abs, abs + 2) === '!=' && source.slice(abs, abs + 3) !== '!==') {
            candidates.push(
              makeCandidate('js.!=_to_!==', snippetForRange(source, abs, abs + 2, '!==')),
            );
          }
        }
      } else if (node.operator === '+') {
        if (isSimpleStringLiteral(node.left) && isSimpleStringLiteral(node.right)) {
          const content = (literalStringValue(node.left) + literalStringValue(node.right))
            .replace(/\\/g, '\\\\')
            .replace(/`/g, '\\`')
            .replace(/\$\{/g, '\\${');
          candidates.push(
            makeCandidate(
              'js.string_concat_plus',
              snippetForRange(source, node.start, node.end, '`' + content + '`'),
            ),
          );
        }
      } else if (
        node.left?.type === 'CallExpression' &&
        memberName(node.left.callee) === 'indexOf'
      ) {
        const rightLit =
          node.right?.type === 'UnaryExpression' &&
          node.right.operator === '-' &&
          node.right.argument?.type === 'Literal' &&
          node.right.argument.value === 1
            ? -1
            : node.right?.type === 'Literal'
              ? node.right.value
              : null;
        if (
          (node.operator === '>=' && rightLit === 0) ||
          ((node.operator === '!==' || node.operator === '!=') && rightLit === -1)
        ) {
          const call = node.left;
          const objSrc = source.slice(call.callee.object.start, call.callee.object.end);
          const inner = callArgSource(source, call);
          candidates.push(
            makeCandidate(
              'js.indexof_to_includes',
              snippetForRange(source, node.start, node.end, `${objSrc}.includes(${inner})`),
            ),
          );
        } else if (
          node.operator === '===' &&
          node.right?.type === 'Literal' &&
          node.right.value === 0
        ) {
          const call = node.left;
          const objSrc = source.slice(call.callee.object.start, call.callee.object.end);
          const inner = callArgSource(source, call);
          candidates.push(
            makeCandidate(
              'js.indexof_zero_to_startswith',
              snippetForRange(source, node.start, node.end, `${objSrc}.startsWith(${inner})`),
            ),
          );
        }
      }
    },

    LogicalExpression(node) {
      if (
        node.operator === '&&' &&
        node.left?.type === 'Identifier' &&
        node.right?.type === 'MemberExpression' &&
        node.right.object?.type === 'Identifier' &&
        node.right.object.name === node.left.name
      ) {
        const clean = node.right.computed
          ? `${node.left.name}?.[${source.slice(node.right.property.start, node.right.property.end)}]`
          : `${node.left.name}?.${memberName(node.right)}`;
        candidates.push(
          makeCandidate(
            'js.nullable_chaining',
            snippetForRange(source, node.start, node.end, clean),
          ),
        );
      }
    },

    CallExpression(node) {
      const prop = memberName(node.callee);
      if (prop === 'substr' && node.callee?.type === 'MemberExpression') {
        const propNode = node.callee.property;
        candidates.push(
          makeCandidate(
            'js.substr_to_substring',
            snippetForRange(source, propNode.start, propNode.end, 'substring'),
          ),
        );
      }
      if (
        prop === 'charAt' &&
        node.arguments?.length === 1 &&
        node.arguments[0].type === 'Literal' &&
        node.arguments[0].value === 0
      ) {
        const propNode = node.callee.property;
        candidates.push(
          makeCandidate(
            'js.charat0_to_at',
            snippetForRange(source, propNode.start, propNode.end, 'at'),
          ),
        );
      }
      if (
        isObjectAssignCall(node) &&
        node.arguments?.length === 2 &&
        isEmptyObjectLiteral(node.arguments[0])
      ) {
        const srcArg = source.slice(node.arguments[1].start, node.arguments[1].end).trim();
        candidates.push(
          makeCandidate(
            'js.object_assign_to_spread',
            snippetForRange(source, node.start, node.end, `({...${srcArg}})`),
          ),
        );
      }
      if (isIdentifier(node.callee, 'escape')) {
        candidates.push(
          makeCandidate(
            'js.escape_to_encodeuri',
            snippetForRange(source, node.callee.start, node.callee.end, 'encodeURI'),
          ),
        );
      }
      if (isIdentifier(node.callee, 'unescape')) {
        candidates.push(
          makeCandidate(
            'js.unescape_to_decodeuri',
            snippetForRange(source, node.callee.start, node.callee.end, 'decodeURI'),
          ),
        );
      }
    },

    AssignmentExpression(node) {
      if (
        node.operator === '=' &&
        node.left?.type === 'MemberExpression' &&
        !node.left.computed &&
        isIdentifier(node.left.object, 'module') &&
        isIdentifier(node.left.property, 'exports')
      ) {
        const right = source.slice(node.right.start, node.right.end);
        candidates.push(
          makeCandidate(
            'js.module_exports_to_export',
            snippetForRange(source, node.start, node.end, `export default ${right}`),
          ),
        );
      }
      if (
        node.operator === '=' &&
        node.left?.type === 'MemberExpression' &&
        !node.left.computed &&
        isIdentifier(node.left.object, 'exports') &&
        node.left.property?.type === 'Identifier'
      ) {
        const name = node.left.property.name;
        const right = source.slice(node.right.start, node.right.end);
        candidates.push(
          makeCandidate(
            'js.exports_dot_to_export',
            snippetForRange(source, node.start, node.end, `export const ${name} = ${right}`),
          ),
        );
      }
    },

    CatchClause(node) {
      if (node.param && node.param.type === 'Identifier' && node.body) {
        if (!findCatchParamUses(node.param, node.body)) {
          const catchKeywordEnd = node.start + 'catch'.length;
          const bodyStart = node.body.start;
          candidates.push(
            makeCandidate(
              'js.optional_catch_binding',
              snippetForRange(source, catchKeywordEnd, bodyStart, ' '),
            ),
          );
        }
      }
    },

    Identifier(node, parent) {
      // Skip property keys / non-value positions where renaming would be wrong.
      if (
        parent &&
        parent.type === 'MemberExpression' &&
        parent.property === node &&
        !parent.computed
      ) {
        return;
      }
      if (parent && parent.type === 'Property' && parent.key === node && !parent.computed) {
        return;
      }
      if (parent && (parent.type === 'FunctionDeclaration' || parent.type === 'VariableDeclarator')
          && (parent.id === node || parent.params?.includes?.(node))) {
        return;
      }
      if (node.name === '__dirname') {
        candidates.push(
          makeCandidate(
            'js.dirname_to_importmeta',
            snippetForRange(source, node.start, node.end, 'import.meta.dirname'),
          ),
        );
      } else if (node.name === '__filename') {
        candidates.push(
          makeCandidate(
            'js.filename_to_importmeta',
            snippetForRange(source, node.start, node.end, 'import.meta.url'),
          ),
        );
      }
    },
  });

  return candidates;
}

function publicCandidates(source) {
  return detectInternal(source).map(
    ({ _start, _end, _replacement, _topLevel, ...pub }) => pub,
  );
}

function isParseable(source) {
  try {
    parseSource(source);
    return true;
  } catch {
    try {
      parseSource(source, 'module');
      return true;
    } catch {
      return false;
    }
  }
}

function apply(source, ruleId, startLine) {
  const all = detectInternal(source);
  const match = all.find((c) => c.ruleId === ruleId && c.startLine === startLine);
  if (!match) {
    return { ok: false, error: `No match for ${ruleId} at line ${startLine}` };
  }

  // Careful apply: CommonJS→ESM import only at program top level.
  if (ruleId === 'js.require_to_import' && match._topLevel === false) {
    return {
      ok: false,
      error: 'js.require_to_import apply refused for non-top-level require',
    };
  }

  // Surgical offset rewrite (preferred — preserves unrelated formatting).
  const next = source.slice(0, match._start) + match._replacement + source.slice(match._end);

  if (!isParseable(next)) {
    // Optional whole-Program codegen fallback for rare cases.
    try {
      const ast = parseSource(source);
      void generate(ast);
    } catch {
      /* ignore */
    }
    return { ok: false, error: 'Apply produced unparseable source' };
  }

  return { ok: true, ruleId, startLine, source: next };
}

function main() {
  const [cmd, file, ruleId, startLineArg] = process.argv.slice(2);
  if (!cmd || !file) {
    console.error('Usage: node ast_engine.mjs detect <file.js>');
    console.error('       node ast_engine.mjs apply <file.js> <ruleId> <startLine>');
    process.exit(2);
  }
  const abs = path.resolve(file);
  if (!fs.existsSync(abs)) {
    if (cmd === 'detect') {
      console.log('[]');
      return;
    }
    console.log(JSON.stringify({ ok: false, error: `File not found: ${abs}` }));
    process.exit(1);
  }
  const source = fs.readFileSync(abs, 'utf8');

  if (cmd === 'detect') {
    console.log(JSON.stringify(publicCandidates(source)));
    return;
  }

  if (cmd === 'apply') {
    const startLine = Number.parseInt(startLineArg, 10);
    if (!ruleId || !Number.isFinite(startLine)) {
      console.log(JSON.stringify({ ok: false, error: 'ruleId and startLine required' }));
      process.exit(1);
    }
    const result = apply(source, ruleId, startLine);
    if (result.ok) {
      fs.writeFileSync(abs, result.source, 'utf8');
      console.log(JSON.stringify({ ok: true, ruleId, startLine }));
    } else {
      console.log(JSON.stringify({ ok: false, error: result.error }));
      process.exit(1);
    }
    return;
  }

  console.error(`Unknown command: ${cmd}`);
  process.exit(2);
}

main();
