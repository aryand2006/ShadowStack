using System.Text;
using System.Text.RegularExpressions;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CSharp;
using Microsoft.CodeAnalysis.CSharp.Syntax;
using Newtonsoft.Json;
using Newtonsoft.Json.Serialization;

namespace CsharpAstEngine;

public static class Program
{
    private static readonly JsonSerializerSettings JsonSettings = new()
    {
        ContractResolver = new CamelCasePropertyNamesContractResolver(),
        Formatting = Formatting.None,
        NullValueHandling = NullValueHandling.Ignore
    };

    public static int Main(string[] args)
    {
        if (args.Length < 2)
        {
            Console.Error.WriteLine("Usage: CsharpAstEngine detect <file.cs>");
            Console.Error.WriteLine("       CsharpAstEngine apply <file.cs> <ruleId> <startLine>");
            return 2;
        }

        var command = args[0].Trim().ToLowerInvariant();
        try
        {
            return command switch
            {
                "detect" => Detect(args[1]),
                "apply" when args.Length >= 4 => Apply(args[1], args[2], int.Parse(args[3])),
                _ => FailUsage()
            };
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine(ex.Message);
            return 1;
        }
    }

    private static int FailUsage()
    {
        Console.Error.WriteLine("Unknown command or missing arguments");
        return 2;
    }

    private static int Detect(string path)
    {
        var source = File.ReadAllText(path);
        var tree = CSharpSyntaxTree.ParseText(source, path: path);
        var findings = RuleEngine.Detect(tree, source);
        Console.WriteLine(JsonConvert.SerializeObject(findings, JsonSettings));
        return 0;
    }

    private static int Apply(string path, string ruleId, int startLine)
    {
        var source = File.ReadAllText(path);
        var tree = CSharpSyntaxTree.ParseText(source, path: path);
        var result = RuleEngine.Apply(tree, source, ruleId, startLine);
        if (!result.Ok)
        {
            Console.WriteLine(JsonConvert.SerializeObject(result, JsonSettings));
            return 1;
        }

        File.WriteAllText(path, result.Source!);
        Console.WriteLine(JsonConvert.SerializeObject(new ApplyResult { Ok = true, RuleId = ruleId }, JsonSettings));
        return 0;
    }
}

public sealed class Finding
{
    public string RuleId { get; set; } = "";
    public string RuleName { get; set; } = "";
    public int StartLine { get; set; }
    public int EndLine { get; set; }
    public string BeforeSnippet { get; set; } = "";
    public string AfterSnippet { get; set; } = "";
    public double Confidence { get; set; }
    public string Risk { get; set; } = "LOW";
}

public sealed class ApplyResult
{
    public bool Ok { get; set; }
    public string? RuleId { get; set; }
    public string? Error { get; set; }
    [JsonIgnore] public string? Source { get; set; }
}

internal static class RuleEngine
{
    public static List<Finding> Detect(SyntaxTree tree, string source)
    {
        var root = tree.GetCompilationUnitRoot();
        var findings = new List<Finding>();

        DetectNullableEnable(root, source, findings);
        DetectFileScopedNamespace(root, source, findings);
        new Detector(source, findings).Visit(root);
        DetectConcurrentDictTryAdd(root, source, findings);
        DetectUsingDeclaration(root, source, findings);
        DetectStringConcat(root, source, findings);
        DetectAsyncTaskReturn(root, source, findings);
        DetectRecordDto(root, source, findings);
        DetectRemotingUsing(root, source, findings);

        return findings
            .GroupBy(f => (f.RuleId, f.StartLine, f.BeforeSnippet))
            .Select(g => g.First())
            .OrderBy(f => f.StartLine)
            .ThenBy(f => f.RuleId, StringComparer.Ordinal)
            .ToList();
    }

    public static ApplyResult Apply(SyntaxTree tree, string source, string ruleId, int startLine)
    {
        var root = tree.GetCompilationUnitRoot();

        if (ruleId == "cs.nullable_enable")
        {
            if (HasNullableEnable(root))
            {
                return new ApplyResult { Ok = false, RuleId = ruleId, Error = "already present" };
            }

            var directive = SyntaxFactory.TriviaList(
                SyntaxFactory.Trivia(
                    SyntaxFactory.NullableDirectiveTrivia(
                        SyntaxFactory.Token(SyntaxKind.EnableKeyword), true)),
                SyntaxFactory.CarriageReturnLineFeed);
            var newRoot = root.WithLeadingTrivia(directive.AddRange(root.GetLeadingTrivia()));
            return new ApplyResult { Ok = true, RuleId = ruleId, Source = newRoot.ToFullString() };
        }

        if (ruleId == "cs.file_scoped_namespace")
        {
            var rewritten = TryConvertFileScopedNamespace(root);
            if (rewritten == null)
            {
                return new ApplyResult { Ok = false, RuleId = ruleId, Error = "not applicable" };
            }

            return new ApplyResult { Ok = true, RuleId = ruleId, Source = rewritten.ToFullString() };
        }

        if (ruleId == "cs.concurrentdict_tryadd")
        {
            var rewritten = TryApplyConcurrentDict(root, startLine);
            if (rewritten == null)
            {
                return new ApplyResult { Ok = false, RuleId = ruleId, Error = "not found" };
            }

            return new ApplyResult { Ok = true, RuleId = ruleId, Source = rewritten.ToFullString() };
        }

        if (ruleId == "cs.using_declaration")
        {
            var rewritten = TryApplyUsingDeclaration(root, startLine);
            if (rewritten == null)
            {
                return new ApplyResult { Ok = false, RuleId = ruleId, Error = "not found" };
            }

            return new ApplyResult { Ok = true, RuleId = ruleId, Source = rewritten.ToFullString() };
        }

        if (ruleId is "cs.string_concat_interpolate" or "cs.string_format_to_interpolation"
            or "cs.stringbuilder_appendformat" or "cs.string_isempty" or "cs.nameof_for_literals"
            or "cs.arraylist_to_list" or "cs.hashtable_to_dictionary"
            or "cs.readonlycollection_to_ilist" or "cs.webclient_to_httpclient"
            or "cs.namevaluecollection_to_dict" or "cs.httprequest_to_httpclient"
            or "cs.asynctask_return" or "cs.record_dto")
        {
            var rewriter = new ApplyingRewriter(ruleId, startLine);
            var newRoot = (CompilationUnitSyntax)rewriter.Visit(root)!;
            if (!rewriter.Applied)
            {
                return new ApplyResult { Ok = false, RuleId = ruleId, Error = "not found" };
            }

            return new ApplyResult { Ok = true, RuleId = ruleId, Source = newRoot.ToFullString() };
        }

        // Detect-only / advisory rules — no safe automatic rewrite.
        return new ApplyResult
        {
            Ok = false,
            RuleId = ruleId,
            Error = "rule is detect-only or unsupported for apply"
        };
    }

    private static void DetectNullableEnable(CompilationUnitSyntax root, string source, List<Finding> findings)
    {
        if (HasNullableEnable(root)) return;
        var firstLine = source.Split('\n')[0].TrimEnd('\r');
        findings.Add(new Finding
        {
            RuleId = "cs.nullable_enable",
            RuleName = "Enable nullable reference type analysis",
            StartLine = 1,
            EndLine = 1,
            BeforeSnippet = firstLine,
            AfterSnippet = "#nullable enable\n" + firstLine,
            Confidence = 0.98,
            Risk = "LOW"
        });
    }

    private static bool HasNullableEnable(CompilationUnitSyntax root)
    {
        return root.DescendantTrivia()
            .Any(t => t.IsKind(SyntaxKind.NullableDirectiveTrivia)
                      && t.ToString().Contains("enable", StringComparison.OrdinalIgnoreCase));
    }

    private static void DetectFileScopedNamespace(CompilationUnitSyntax root, string source, List<Finding> findings)
    {
        foreach (var ns in root.Members.OfType<NamespaceDeclarationSyntax>())
        {
            if (ns.OpenBraceToken.IsMissing) continue;
            var line = LineOf(ns.NamespaceKeyword, source);
            var before = LineText(source, line);
            var after = "namespace " + ns.Name + ";";
            findings.Add(new Finding
            {
                RuleId = "cs.file_scoped_namespace",
                RuleName = "Block namespace → file-scoped namespace",
                StartLine = line,
                EndLine = line,
                BeforeSnippet = before,
                AfterSnippet = after,
                Confidence = 0.90,
                Risk = "LOW"
            });
        }
    }

    private static CompilationUnitSyntax? TryConvertFileScopedNamespace(CompilationUnitSyntax root)
    {
        var block = root.Members.OfType<NamespaceDeclarationSyntax>().FirstOrDefault();
        if (block == null) return null;
        var fileScoped = SyntaxFactory.FileScopedNamespaceDeclaration(block.Name)
            .WithMembers(block.Members)
            .WithUsings(block.Usings)
            .WithExterns(block.Externs)
            .WithAttributeLists(block.AttributeLists)
            .WithLeadingTrivia(block.GetLeadingTrivia())
            .WithTrailingTrivia(SyntaxFactory.ElasticCarriageReturnLineFeed);
        return root.ReplaceNode(block, fileScoped);
    }

    private static void DetectRemotingUsing(CompilationUnitSyntax root, string source, List<Finding> findings)
    {
        foreach (var u in root.Usings)
        {
            var name = u.Name?.ToString() ?? "";
            if (name.StartsWith("System.Runtime.Remoting", StringComparison.Ordinal))
            {
                var line = LineOf(u.GetFirstToken(), source);
                findings.Add(new Finding
                {
                    RuleId = "cs.remoting_removed",
                    RuleName = ".NET Remoting removed from modern .NET",
                    StartLine = line,
                    EndLine = line,
                    BeforeSnippet = LineText(source, line),
                    AfterSnippet = "/* removed: migrate to IPC or HTTP */ " + LineText(source, line),
                    Confidence = 0.99,
                    Risk = "CRITICAL"
                });
            }
        }
    }

    private static void DetectConcurrentDictTryAdd(CompilationUnitSyntax root, string source, List<Finding> findings)
    {
        foreach (var ifStmt in root.DescendantNodes().OfType<IfStatementSyntax>())
        {
            if (ifStmt.Else != null) continue;
            if (ifStmt.Condition is not PrefixUnaryExpressionSyntax
                {
                    RawKind: (int)SyntaxKind.LogicalNotExpression,
                    Operand: InvocationExpressionSyntax inv
                }) continue;

            if (inv.Expression is not MemberAccessExpressionSyntax
                {
                    Name.Identifier.Text: "ContainsKey",
                    Expression: var dictExpr
                }) continue;

            if (inv.ArgumentList.Arguments.Count != 1) continue;
            var keyArg = inv.ArgumentList.Arguments[0].Expression;

            StatementSyntax body = ifStmt.Statement is BlockSyntax { Statements.Count: 1 } b
                ? b.Statements[0]
                : ifStmt.Statement;

            if (body is not ExpressionStatementSyntax
                {
                    Expression: AssignmentExpressionSyntax
                    {
                        Left: ElementAccessExpressionSyntax
                        {
                            Expression: var leftDict,
                            ArgumentList.Arguments: { Count: 1 } idxArgs
                        },
                        Right: var valueExpr
                    }
                }) continue;

            if (!dictExpr.IsEquivalentTo(leftDict)) continue;
            if (!keyArg.IsEquivalentTo(idxArgs[0].Expression)) continue;

            var line = LineOf(ifStmt.GetFirstToken(), source);
            var endLine = LineOf(ifStmt.GetLastToken(), source);
            var before = Snippet(source, line, endLine);
            var after = $"{dictExpr}.TryAdd({keyArg}, {valueExpr});";
            findings.Add(new Finding
            {
                RuleId = "cs.concurrentdict_tryadd",
                RuleName = "ContainsKey + assignment → atomic TryAdd",
                StartLine = line,
                EndLine = endLine,
                BeforeSnippet = before,
                AfterSnippet = after,
                Confidence = 0.80,
                Risk = "MODERATE"
            });
        }
    }

    private static CompilationUnitSyntax? TryApplyConcurrentDict(CompilationUnitSyntax root, int startLine)
    {
        foreach (var ifStmt in root.DescendantNodes().OfType<IfStatementSyntax>())
        {
            if (LineOf(ifStmt.GetFirstToken(), root.SyntaxTree.GetText().ToString()) != startLine)
            {
                // Fall through with text-based line from map
            }

            var spanLine = root.SyntaxTree.GetLineSpan(ifStmt.Span).StartLinePosition.Line + 1;
            if (spanLine != startLine) continue;
            if (ifStmt.Else != null) continue;
            if (ifStmt.Condition is not PrefixUnaryExpressionSyntax
                {
                    RawKind: (int)SyntaxKind.LogicalNotExpression,
                    Operand: InvocationExpressionSyntax inv
                }) continue;
            if (inv.Expression is not MemberAccessExpressionSyntax
                {
                    Name.Identifier.Text: "ContainsKey",
                    Expression: var dictExpr
                }) continue;
            if (inv.ArgumentList.Arguments.Count != 1) continue;
            var keyArg = inv.ArgumentList.Arguments[0].Expression;
            StatementSyntax body = ifStmt.Statement is BlockSyntax { Statements.Count: 1 } b
                ? b.Statements[0]
                : ifStmt.Statement;
            if (body is not ExpressionStatementSyntax
                {
                    Expression: AssignmentExpressionSyntax
                    {
                        Left: ElementAccessExpressionSyntax
                        {
                            Expression: var leftDict,
                            ArgumentList.Arguments: { Count: 1 } idxArgs
                        },
                        Right: var valueExpr
                    }
                }) continue;
            if (!dictExpr.IsEquivalentTo(leftDict)) continue;
            if (!keyArg.IsEquivalentTo(idxArgs[0].Expression)) continue;

            var call = SyntaxFactory.ExpressionStatement(
                SyntaxFactory.InvocationExpression(
                        SyntaxFactory.MemberAccessExpression(
                            SyntaxKind.SimpleMemberAccessExpression,
                            dictExpr,
                            SyntaxFactory.IdentifierName("TryAdd")))
                    .WithArgumentList(SyntaxFactory.ArgumentList(SyntaxFactory.SeparatedList(new[]
                    {
                        SyntaxFactory.Argument(keyArg),
                        SyntaxFactory.Argument(valueExpr)
                    }))));
            return root.ReplaceNode(ifStmt, call.WithTriviaFrom(ifStmt));
        }

        return null;
    }

    private static void DetectUsingDeclaration(CompilationUnitSyntax root, string source, List<Finding> findings)
    {
        foreach (var usingStmt in root.DescendantNodes().OfType<UsingStatementSyntax>())
        {
            if (usingStmt.Declaration == null || usingStmt.Declaration.Variables.Count != 1) continue;
            if (usingStmt.Statement is not BlockSyntax block) continue;
            // Only suggest when the using owns a simple block (C# 8 using declaration).
            var line = LineOf(usingStmt.UsingKeyword, source);
            var decl = usingStmt.Declaration.ToString().Trim();
            var after = "using " + decl + ";";
            findings.Add(new Finding
            {
                RuleId = "cs.using_declaration",
                RuleName = "using statement → using declaration (C# 8)",
                StartLine = line,
                EndLine = line,
                BeforeSnippet = LineText(source, line),
                AfterSnippet = after,
                Confidence = 0.85,
                Risk = "LOW"
            });
            _ = block;
        }
    }

    private static CompilationUnitSyntax? TryApplyUsingDeclaration(CompilationUnitSyntax root, int startLine)
    {
        foreach (var usingStmt in root.DescendantNodes().OfType<UsingStatementSyntax>())
        {
            var spanLine = root.SyntaxTree.GetLineSpan(usingStmt.Span).StartLinePosition.Line + 1;
            if (spanLine != startLine) continue;
            if (usingStmt.Declaration == null || usingStmt.Declaration.Variables.Count != 1) continue;
            if (usingStmt.Statement is not BlockSyntax block) continue;

            var usingDecl = SyntaxFactory.LocalDeclarationStatement(usingStmt.Declaration)
                .WithUsingKeyword(SyntaxFactory.Token(SyntaxKind.UsingKeyword))
                .WithSemicolonToken(SyntaxFactory.Token(SyntaxKind.SemicolonToken))
                .WithLeadingTrivia(usingStmt.GetLeadingTrivia())
                .WithTrailingTrivia(SyntaxFactory.ElasticCarriageReturnLineFeed);

            var replacement = new List<StatementSyntax> { usingDecl };
            replacement.AddRange(block.Statements);
            if (usingStmt.Parent is BlockSyntax parentBlock)
            {
                var stmts = parentBlock.Statements.ToList();
                var idx = stmts.IndexOf(usingStmt);
                if (idx < 0) return null;
                stmts.RemoveAt(idx);
                stmts.InsertRange(idx, replacement);
                return root.ReplaceNode(parentBlock,
                    parentBlock.WithStatements(SyntaxFactory.List(stmts)));
            }
        }

        return null;
    }

    private static void DetectStringConcat(CompilationUnitSyntax root, string source, List<Finding> findings)
    {
        foreach (var bin in root.DescendantNodes().OfType<BinaryExpressionSyntax>())
        {
            if (!bin.IsKind(SyntaxKind.AddExpression)) continue;
            if (!LooksLikeStringConcat(bin)) continue;
            // Prefer top-most concat chain
            if (bin.Parent is BinaryExpressionSyntax parent && parent.IsKind(SyntaxKind.AddExpression)
                && LooksLikeStringConcat(parent)) continue;

            var line = LineOf(bin.GetFirstToken(), source);
            var interpolated = TryBuildInterpolation(bin);
            if (interpolated == null) continue;
            findings.Add(new Finding
            {
                RuleId = "cs.string_concat_interpolate",
                RuleName = "String concatenation → interpolation",
                StartLine = line,
                EndLine = LineOf(bin.GetLastToken(), source),
                BeforeSnippet = bin.ToString(),
                AfterSnippet = interpolated,
                Confidence = 0.88,
                Risk = "LOW"
            });
        }
    }

    private static bool LooksLikeStringConcat(BinaryExpressionSyntax bin)
    {
        return ContainsStringLiteral(bin);
    }

    private static bool ContainsStringLiteral(ExpressionSyntax expr) =>
        expr switch
        {
            LiteralExpressionSyntax lit when lit.IsKind(SyntaxKind.StringLiteralExpression) => true,
            BinaryExpressionSyntax b when b.IsKind(SyntaxKind.AddExpression) =>
                ContainsStringLiteral(b.Left) || ContainsStringLiteral(b.Right),
            _ => false
        };

    private static string? TryBuildInterpolation(ExpressionSyntax expr)
    {
        var parts = new List<(bool isLiteral, string text)>();
        if (!FlattenConcat(expr, parts)) return null;
        if (parts.Count < 2) return null;
        if (!parts.Any(p => p.isLiteral)) return null;

        var sb = new StringBuilder("$\"");
        foreach (var (isLiteral, text) in parts)
        {
            if (isLiteral)
            {
                sb.Append(text.Replace("\"", "\\\"", StringComparison.Ordinal));
            }
            else
            {
                sb.Append('{').Append(text).Append('}');
            }
        }

        sb.Append('"');
        return sb.ToString();
    }

    private static bool FlattenConcat(ExpressionSyntax expr, List<(bool isLiteral, string text)> parts)
    {
        if (expr is BinaryExpressionSyntax bin && bin.IsKind(SyntaxKind.AddExpression))
        {
            return FlattenConcat(bin.Left, parts) && FlattenConcat(bin.Right, parts);
        }

        if (expr is LiteralExpressionSyntax lit && lit.IsKind(SyntaxKind.StringLiteralExpression))
        {
            parts.Add((true, lit.Token.ValueText));
            return true;
        }

        // Keep simple identifiers / member access as holes
        if (expr is IdentifierNameSyntax or MemberAccessExpressionSyntax or ElementAccessExpressionSyntax
            or InvocationExpressionSyntax or ParenthesizedExpressionSyntax)
        {
            parts.Add((false, expr.ToString()));
            return true;
        }

        return false;
    }

    private static void DetectAsyncTaskReturn(CompilationUnitSyntax root, string source, List<Finding> findings)
    {
        foreach (var method in root.DescendantNodes().OfType<MethodDeclarationSyntax>())
        {
            if (method.Modifiers.Any(SyntaxKind.AsyncKeyword)) continue;
            var returnType = method.ReturnType.ToString();
            if (returnType is not ("Task" or "Task<T>") && !returnType.StartsWith("Task<", StringComparison.Ordinal))
            {
                continue;
            }

            if (method.Body == null || method.Body.Statements.Count != 1) continue;

            // Method returns Task but body only returns Task.CompletedTask / Task.FromResult(...)
            ExpressionSyntax? returned = method.Body.Statements[0] is ReturnStatementSyntax rs
                ? rs.Expression
                : null;
            var isCompletedTask = returned is MemberAccessExpressionSyntax
            {
                Expression: IdentifierNameSyntax { Identifier.Text: "Task" },
                Name.Identifier.Text: "CompletedTask"
            };
            var isFromResult = returned is InvocationExpressionSyntax
            {
                Expression: MemberAccessExpressionSyntax
                {
                    Expression: IdentifierNameSyntax { Identifier.Text: "Task" },
                    Name.Identifier.Text: "FromResult"
                }
            };
            if (!method.Identifier.Text.EndsWith("Async", StringComparison.Ordinal)
                && (isCompletedTask || isFromResult))
            {
                var line = LineOf(method.ReturnType.GetFirstToken(), source);
                findings.Add(new Finding
                {
                    RuleId = "cs.asynctask_return",
                    RuleName = "Task-returning method → async Task",
                    StartLine = line,
                    EndLine = line,
                    BeforeSnippet = LineText(source, line),
                    AfterSnippet = LineText(source, line).Contains("async", StringComparison.Ordinal)
                        ? LineText(source, line)
                        : LineText(source, line).Replace("public Task", "public async Task", StringComparison.Ordinal)
                            .Replace("Task ", "async Task ", StringComparison.Ordinal),
                    Confidence = 0.70,
                    Risk = "MODERATE"
                });
            }
        }
    }

    private static void DetectRecordDto(CompilationUnitSyntax root, string source, List<Finding> findings)
    {
        foreach (var cls in root.DescendantNodes().OfType<ClassDeclarationSyntax>())
        {
            if (!cls.Identifier.Text.EndsWith("Dto", StringComparison.OrdinalIgnoreCase)
                && !cls.Identifier.Text.EndsWith("Model", StringComparison.OrdinalIgnoreCase)
                && !cls.Identifier.Text.EndsWith("Record", StringComparison.OrdinalIgnoreCase))
            {
                // Also detect pure property-bag classes with only auto-properties
            }

            var isDtoName = cls.Identifier.Text.EndsWith("Dto", StringComparison.OrdinalIgnoreCase)
                            || cls.Identifier.Text.EndsWith("Model", StringComparison.OrdinalIgnoreCase);
            var members = cls.Members;
            if (members.Count == 0) continue;
            var onlyAutoProps = members.All(m =>
                m is PropertyDeclarationSyntax p
                && p.AccessorList != null
                && p.AccessorList.Accessors.All(a => a.Body == null && a.ExpressionBody == null));
            if (!onlyAutoProps) continue;
            if (!isDtoName && members.Count < 2) continue;

            var line = LineOf(cls.Keyword, source);
            var propList = string.Join(", ",
                members.OfType<PropertyDeclarationSyntax>()
                    .Select(p => $"{p.Type} {p.Identifier}"));
            findings.Add(new Finding
            {
                RuleId = "cs.record_dto",
                RuleName = "Property-bag class → record",
                StartLine = line,
                EndLine = line,
                BeforeSnippet = LineText(source, line),
                AfterSnippet = $"public record {cls.Identifier}({propList});",
                Confidence = isDtoName ? 0.82 : 0.65,
                Risk = "MODERATE"
            });
        }
    }

    internal static int LineOf(SyntaxToken token, string source)
    {
        var tree = token.SyntaxTree;
        if (tree != null)
        {
            return tree.GetLineSpan(token.Span).StartLinePosition.Line + 1;
        }

        return source.Take(token.SpanStart).Count(c => c == '\n') + 1;
    }

    internal static string LineText(string source, int lineNumber)
    {
        var lines = source.Split('\n');
        if (lineNumber < 1 || lineNumber > lines.Length) return "";
        return lines[lineNumber - 1].TrimEnd('\r');
    }

    internal static string Snippet(string source, int startLine, int endLine)
    {
        var lines = source.Split('\n');
        var sb = new StringBuilder();
        for (var i = startLine; i <= endLine && i <= lines.Length; i++)
        {
            if (sb.Length > 0) sb.Append('\n');
            sb.Append(lines[i - 1].TrimEnd('\r'));
        }

        return sb.ToString();
    }
}

internal sealed class Detector : CSharpSyntaxWalker
{
    private readonly string _source;
    private readonly List<Finding> _findings;

    public Detector(string source, List<Finding> findings) : base(SyntaxWalkerDepth.Trivia)
    {
        _source = source;
        _findings = findings;
    }

    public override void VisitIdentifierName(IdentifierNameSyntax node)
    {
        var name = node.Identifier.Text;
        switch (name)
        {
            case "ArrayList":
                AddTypeReplace(node, "cs.arraylist_to_list", "ArrayList → List<object>",
                    "List<object>", 0.88, "MODERATE");
                break;
            case "Hashtable":
                AddTypeReplace(node, "cs.hashtable_to_dictionary",
                    "Hashtable → Dictionary<object, object>",
                    "Dictionary<object, object>", 0.86, "MODERATE");
                break;
            case "WebClient":
                AddTypeReplace(node, "cs.webclient_to_httpclient", "WebClient → HttpClient",
                    "HttpClient", 0.72, "HIGH");
                break;
            case "BinaryFormatter":
                AddAdvisory(node, "cs.binaryformatter_removed",
                    "BinaryFormatter removed from modern .NET",
                    "/* removed: choose a safe serializer */ BinaryFormatter",
                    0.99, "CRITICAL");
                break;
            case "HttpWebRequest":
                AddTypeReplace(node, "cs.httprequest_to_httpclient",
                    "HttpWebRequest → HttpClient", "HttpClient", 0.70, "HIGH");
                break;
            case "NameValueCollection":
                AddTypeReplace(node, "cs.namevaluecollection_to_dict",
                    "NameValueCollection → Dictionary", "Dictionary<string, string>",
                    0.75, "MODERATE");
                break;
            case "PrincipalPermission":
                AddAdvisory(node, "cs.principalpermission_removed",
                    "PrincipalPermission removed",
                    "/* replace with explicit authorization */ PrincipalPermission",
                    0.98, "CRITICAL");
                break;
            case "ConfigurationManager":
                if (node.Parent is MemberAccessExpressionSyntax
                    {
                        Name.Identifier.Text: "AppSettings"
                    })
                {
                    AddAdvisory(node, "cs.configurationmanager_to_iconfiguration",
                        "ConfigurationManager.AppSettings → IConfiguration",
                        "/* inject IConfiguration */ configuration",
                        0.62, "HIGH");
                }

                break;
        }

        base.VisitIdentifierName(node);
    }

    public override void VisitGenericName(GenericNameSyntax node)
    {
        if (node.Identifier.Text == "ReadOnlyCollection" && node.TypeArgumentList.Arguments.Count == 1)
        {
            var typeArg = node.TypeArgumentList.Arguments[0].ToString();
            var line = RuleEngine.LineOf(node.Identifier, _source);
            _findings.Add(new Finding
            {
                RuleId = "cs.readonlycollection_to_ilist",
                RuleName = "ReadOnlyCollection<T> → IReadOnlyList<T>",
                StartLine = line,
                EndLine = line,
                BeforeSnippet = RuleEngine.LineText(_source, line),
                AfterSnippet = RuleEngine.LineText(_source, line)
                    .Replace(node.ToString(), $"IReadOnlyList<{typeArg}>", StringComparison.Ordinal),
                Confidence = 0.68,
                Risk = "MODERATE"
            });
        }

        base.VisitGenericName(node);
    }

    public override void VisitInvocationExpression(InvocationExpressionSyntax node)
    {
        // string.Format("…{0}…", x)
        if (node.Expression is MemberAccessExpressionSyntax
            {
                Expression: PredefinedTypeSyntax { Keyword.Text: "string" }
                    or IdentifierNameSyntax { Identifier.Text: "string" },
                Name.Identifier.Text: "Format"
            }
            && node.ArgumentList.Arguments.Count == 2
            && node.ArgumentList.Arguments[0].Expression is LiteralExpressionSyntax formatLit
            && formatLit.IsKind(SyntaxKind.StringLiteralExpression))
        {
            var fmt = formatLit.Token.ValueText;
            if (fmt.Contains("{0}", StringComparison.Ordinal)
                && !Regex.IsMatch(fmt, @"\{[1-9]"))
            {
                var arg = node.ArgumentList.Arguments[1].Expression.ToString();
                var interpolated = "$\"" + fmt.Replace("{0}", "{" + arg + "}", StringComparison.Ordinal) + "\"";
                var line = RuleEngine.LineOf(node.GetFirstToken(), _source);
                _findings.Add(new Finding
                {
                    RuleId = "cs.string_format_to_interpolation",
                    RuleName = "string.Format → string interpolation",
                    StartLine = line,
                    EndLine = line,
                    BeforeSnippet = RuleEngine.LineText(_source, line),
                    AfterSnippet = RuleEngine.LineText(_source, line)
                        .Replace(node.ToString(), interpolated, StringComparison.Ordinal),
                    Confidence = 0.92,
                    Risk = "LOW"
                });
            }
        }

        // sb.AppendFormat("…{0}…", x)
        if (node.Expression is MemberAccessExpressionSyntax
            {
                Name.Identifier.Text: "AppendFormat",
                Expression: var receiver
            }
            && node.ArgumentList.Arguments.Count == 2
            && node.ArgumentList.Arguments[0].Expression is LiteralExpressionSyntax afLit
            && afLit.IsKind(SyntaxKind.StringLiteralExpression))
        {
            var fmt = afLit.Token.ValueText;
            if (fmt.Contains("{0}", StringComparison.Ordinal)
                && !Regex.IsMatch(fmt, @"\{[1-9]"))
            {
                var arg = node.ArgumentList.Arguments[1].Expression.ToString();
                var interpolation = fmt.Replace("{0}", "{" + arg + "}", StringComparison.Ordinal);
                var replacement = $"{receiver}.Append($\"{interpolation}\")";
                var line = RuleEngine.LineOf(node.GetFirstToken(), _source);
                _findings.Add(new Finding
                {
                    RuleId = "cs.stringbuilder_appendformat",
                    RuleName = "StringBuilder.AppendFormat → interpolated Append",
                    StartLine = line,
                    EndLine = line,
                    BeforeSnippet = RuleEngine.LineText(_source, line),
                    AfterSnippet = RuleEngine.LineText(_source, line)
                        .Replace(node.ToString(), replacement, StringComparison.Ordinal),
                    Confidence = 0.90,
                    Risk = "LOW"
                });
            }
        }

        // Thread.Abort(
        if (node.Expression is MemberAccessExpressionSyntax
            {
                Name.Identifier.Text: "Abort",
                Expression: IdentifierNameSyntax { Identifier.Text: "Thread" }
                    or MemberAccessExpressionSyntax
                    {
                        Name.Identifier.Text: "CurrentThread"
                    }
            })
        {
            var line = RuleEngine.LineOf(node.GetFirstToken(), _source);
            _findings.Add(new Finding
            {
                RuleId = "cs.threadabort_removed",
                RuleName = "Thread.Abort → cooperative cancellation",
                StartLine = line,
                EndLine = line,
                BeforeSnippet = RuleEngine.LineText(_source, line),
                AfterSnippet = "/* use CancellationToken */ " + RuleEngine.LineText(_source, line),
                Confidence = 0.98,
                Risk = "CRITICAL"
            });
        }

        // BeginInvoke / EndInvoke
        if (node.Expression is IdentifierNameSyntax { Identifier.Text: "BeginInvoke" or "EndInvoke" }
            or MemberAccessExpressionSyntax { Name.Identifier.Text: "BeginInvoke" or "EndInvoke" })
        {
            var line = RuleEngine.LineOf(node.GetFirstToken(), _source);
            _findings.Add(new Finding
            {
                RuleId = "cs.asynchronous_begin_end",
                RuleName = "Begin/End async pattern → Task-based async",
                StartLine = line,
                EndLine = line,
                BeforeSnippet = RuleEngine.LineText(_source, line),
                AfterSnippet = "/* migrate to Task-based async */ " + node,
                Confidence = 0.82,
                Risk = "HIGH"
            });
        }

        // WebRequest.Create
        if (node.Expression is MemberAccessExpressionSyntax
            {
                Name.Identifier.Text: "Create",
                Expression: IdentifierNameSyntax { Identifier.Text: "WebRequest" }
            })
        {
            var line = RuleEngine.LineOf(node.GetFirstToken(), _source);
            _findings.Add(new Finding
            {
                RuleId = "cs.webrequest_to_httpclient",
                RuleName = "WebRequest.Create → HttpClient",
                StartLine = line,
                EndLine = line,
                BeforeSnippet = RuleEngine.LineText(_source, line),
                AfterSnippet = "/* use HttpClient */ HttpClient",
                Confidence = 0.68,
                Risk = "HIGH"
            });
        }

        // x.Equals("")
        if (node.Expression is MemberAccessExpressionSyntax
            {
                Name.Identifier.Text: "Equals",
                Expression: var target
            }
            && node.ArgumentList.Arguments.Count == 1
            && node.ArgumentList.Arguments[0].Expression is LiteralExpressionSyntax eqLit
            && eqLit.IsKind(SyntaxKind.StringLiteralExpression)
            && eqLit.Token.ValueText == "")
        {
            var line = RuleEngine.LineOf(node.GetFirstToken(), _source);
            var replacement = $"string.IsNullOrEmpty({target})";
            _findings.Add(new Finding
            {
                RuleId = "cs.string_isempty",
                RuleName = "Empty-string comparison → string.IsNullOrEmpty",
                StartLine = line,
                EndLine = line,
                BeforeSnippet = RuleEngine.LineText(_source, line),
                AfterSnippet = RuleEngine.LineText(_source, line)
                    .Replace(node.ToString(), replacement, StringComparison.Ordinal),
                Confidence = 0.74,
                Risk = "MODERATE"
            });
        }

        base.VisitInvocationExpression(node);
    }

    public override void VisitObjectCreationExpression(ObjectCreationExpressionSyntax node)
    {
        if ((node.Type is IdentifierNameSyntax { Identifier.Text: "ArgumentNullException" }
             || node.Type is QualifiedNameSyntax
             {
                 Right: IdentifierNameSyntax { Identifier.Text: "ArgumentNullException" }
             })
            && node.ArgumentList?.Arguments.Count == 1
            && node.ArgumentList.Arguments[0].Expression is LiteralExpressionSyntax lit
            && lit.IsKind(SyntaxKind.StringLiteralExpression))
        {
            var name = lit.Token.ValueText;
            if (SyntaxFacts.IsValidIdentifier(name))
            {
                var line = RuleEngine.LineOf(node.GetFirstToken(), _source);
                var replacement = $"new ArgumentNullException(nameof({name}))";
                _findings.Add(new Finding
                {
                    RuleId = "cs.nameof_for_literals",
                    RuleName = "ArgumentNullException string literal → nameof",
                    StartLine = line,
                    EndLine = line,
                    BeforeSnippet = RuleEngine.LineText(_source, line),
                    AfterSnippet = RuleEngine.LineText(_source, line)
                        .Replace(node.ToString(), replacement, StringComparison.Ordinal),
                    Confidence = 0.98,
                    Risk = "LOW"
                });
            }
        }

        base.VisitObjectCreationExpression(node);
    }

    public override void VisitBinaryExpression(BinaryExpressionSyntax node)
    {
        if (node.IsKind(SyntaxKind.EqualsExpression)
            && node.Right is LiteralExpressionSyntax lit
            && lit.IsKind(SyntaxKind.StringLiteralExpression)
            && lit.Token.ValueText == ""
            && node.Left is IdentifierNameSyntax or MemberAccessExpressionSyntax)
        {
            var line = RuleEngine.LineOf(node.GetFirstToken(), _source);
            var replacement = $"string.IsNullOrEmpty({node.Left})";
            _findings.Add(new Finding
            {
                RuleId = "cs.string_isempty",
                RuleName = "Empty-string comparison → string.IsNullOrEmpty",
                StartLine = line,
                EndLine = line,
                BeforeSnippet = RuleEngine.LineText(_source, line),
                AfterSnippet = RuleEngine.LineText(_source, line)
                    .Replace(node.ToString(), replacement, StringComparison.Ordinal),
                Confidence = 0.74,
                Risk = "MODERATE"
            });
        }

        base.VisitBinaryExpression(node);
    }

    private void AddTypeReplace(IdentifierNameSyntax node, string ruleId, string ruleName,
        string replacement, double confidence, string risk)
    {
        // Skip nameof(...) and member access left side like Namespace.ArrayList when qualified oddly
        if (node.Parent is NameMemberCrefSyntax) return;
        var line = RuleEngine.LineOf(node.Identifier, _source);
        var lineText = RuleEngine.LineText(_source, line);
        _findings.Add(new Finding
        {
            RuleId = ruleId,
            RuleName = ruleName,
            StartLine = line,
            EndLine = line,
            BeforeSnippet = lineText,
            AfterSnippet = ReplaceFirstIdentifier(lineText, node.Identifier.Text, replacement),
            Confidence = confidence,
            Risk = risk
        });
    }

    private void AddAdvisory(IdentifierNameSyntax node, string ruleId, string ruleName,
        string afterHint, double confidence, string risk)
    {
        var line = RuleEngine.LineOf(node.Identifier, _source);
        _findings.Add(new Finding
        {
            RuleId = ruleId,
            RuleName = ruleName,
            StartLine = line,
            EndLine = line,
            BeforeSnippet = RuleEngine.LineText(_source, line),
            AfterSnippet = afterHint,
            Confidence = confidence,
            Risk = risk
        });
    }

    private static string ReplaceFirstIdentifier(string line, string identifier, string replacement)
    {
        var pattern = $@"\b{Regex.Escape(identifier)}\b";
        return Regex.Replace(line, pattern, replacement, RegexOptions.None, TimeSpan.FromSeconds(1));
    }
}

internal sealed class ApplyingRewriter : CSharpSyntaxRewriter
{
    private readonly string _ruleId;
    private readonly int _startLine;
    public bool Applied { get; private set; }

    public ApplyingRewriter(string ruleId, int startLine)
    {
        _ruleId = ruleId;
        _startLine = startLine;
    }

    private bool AtLine(SyntaxNode node)
    {
        var tree = node.SyntaxTree;
        if (tree == null) return false;
        return tree.GetLineSpan(node.Span).StartLinePosition.Line + 1 == _startLine;
    }

    private static bool IsTypeRenameRule(string ruleId) =>
        ruleId is "cs.arraylist_to_list" or "cs.hashtable_to_dictionary"
            or "cs.webclient_to_httpclient" or "cs.httprequest_to_httpclient"
            or "cs.namevaluecollection_to_dict" or "cs.readonlycollection_to_ilist";

    public override SyntaxNode? VisitIdentifierName(IdentifierNameSyntax node)
    {
        if (!AtLine(node)) return base.VisitIdentifierName(node);

        SyntaxNode? replacement = _ruleId switch
        {
            "cs.arraylist_to_list" when node.Identifier.Text == "ArrayList"
                => SyntaxFactory.ParseTypeName("List<object>").WithTriviaFrom(node),
            "cs.hashtable_to_dictionary" when node.Identifier.Text == "Hashtable"
                => SyntaxFactory.ParseTypeName("Dictionary<object, object>").WithTriviaFrom(node),
            "cs.webclient_to_httpclient" when node.Identifier.Text == "WebClient"
                => SyntaxFactory.IdentifierName("HttpClient").WithTriviaFrom(node),
            "cs.httprequest_to_httpclient" when node.Identifier.Text == "HttpWebRequest"
                => SyntaxFactory.IdentifierName("HttpClient").WithTriviaFrom(node),
            "cs.namevaluecollection_to_dict" when node.Identifier.Text == "NameValueCollection"
                => SyntaxFactory.ParseTypeName("Dictionary<string, string>").WithTriviaFrom(node),
            _ => null
        };

        if (replacement != null)
        {
            Applied = true;
            return replacement;
        }

        return base.VisitIdentifierName(node);
    }

    public override SyntaxNode? VisitGenericName(GenericNameSyntax node)
    {
        if (AtLine(node) && _ruleId == "cs.readonlycollection_to_ilist"
            && node.Identifier.Text == "ReadOnlyCollection"
            && node.TypeArgumentList.Arguments.Count == 1)
        {
            Applied = true;
            return SyntaxFactory.GenericName(SyntaxFactory.Identifier("IReadOnlyList"))
                .WithTypeArgumentList(node.TypeArgumentList)
                .WithTriviaFrom(node);
        }

        return base.VisitGenericName(node);
    }

    public override SyntaxNode? VisitInvocationExpression(InvocationExpressionSyntax node)
    {
        if (Applied && !IsTypeRenameRule(_ruleId)) return base.VisitInvocationExpression(node);
        if (!AtLine(node)) return base.VisitInvocationExpression(node);

        if (_ruleId == "cs.string_format_to_interpolation"
            && node.Expression is MemberAccessExpressionSyntax
            {
                Name.Identifier.Text: "Format"
            }
            && node.ArgumentList.Arguments.Count == 2
            && node.ArgumentList.Arguments[0].Expression is LiteralExpressionSyntax formatLit)
        {
            var fmt = formatLit.Token.ValueText;
            var arg = node.ArgumentList.Arguments[1].Expression.ToString();
            var interpolated = "$\"" + fmt.Replace("{0}", "{" + arg + "}", StringComparison.Ordinal) + "\"";
            Applied = true;
            return SyntaxFactory.ParseExpression(interpolated).WithTriviaFrom(node);
        }

        if (_ruleId == "cs.stringbuilder_appendformat"
            && node.Expression is MemberAccessExpressionSyntax
            {
                Name.Identifier.Text: "AppendFormat",
                Expression: var receiver
            }
            && node.ArgumentList.Arguments.Count == 2
            && node.ArgumentList.Arguments[0].Expression is LiteralExpressionSyntax afLit)
        {
            var fmt = afLit.Token.ValueText;
            var arg = node.ArgumentList.Arguments[1].Expression.ToString();
            var interpolation = fmt.Replace("{0}", "{" + arg + "}", StringComparison.Ordinal);
            var expr = SyntaxFactory.ParseExpression($"{receiver}.Append($\"{interpolation}\")");
            Applied = true;
            return expr.WithTriviaFrom(node);
        }

        if (_ruleId == "cs.string_isempty"
            && node.Expression is MemberAccessExpressionSyntax
            {
                Name.Identifier.Text: "Equals",
                Expression: var target
            }
            && node.ArgumentList.Arguments.Count == 1
            && node.ArgumentList.Arguments[0].Expression is LiteralExpressionSyntax
            {
                Token.ValueText: ""
            })
        {
            Applied = true;
            return SyntaxFactory.ParseExpression($"string.IsNullOrEmpty({target})")
                .WithTriviaFrom(node);
        }

        return base.VisitInvocationExpression(node);
    }

    public override SyntaxNode? VisitObjectCreationExpression(ObjectCreationExpressionSyntax node)
    {
        // Still rewrite type names inside object creation via VisitIdentifierName / VisitGenericName.
        if (!Applied && AtLine(node) && _ruleId == "cs.nameof_for_literals"
            && node.Type is IdentifierNameSyntax { Identifier.Text: "ArgumentNullException" }
            && node.ArgumentList?.Arguments.Count == 1
            && node.ArgumentList.Arguments[0].Expression is LiteralExpressionSyntax lit
            && lit.IsKind(SyntaxKind.StringLiteralExpression))
        {
            var name = lit.Token.ValueText;
            Applied = true;
            return SyntaxFactory.ParseExpression($"new ArgumentNullException(nameof({name}))")
                .WithTriviaFrom(node);
        }

        return base.VisitObjectCreationExpression(node);
    }

    public override SyntaxNode? VisitBinaryExpression(BinaryExpressionSyntax node)
    {
        if (!Applied && AtLine(node) && _ruleId == "cs.string_isempty"
            && node.IsKind(SyntaxKind.EqualsExpression)
            && node.Right is LiteralExpressionSyntax { Token.ValueText: "" })
        {
            Applied = true;
            return SyntaxFactory.ParseExpression($"string.IsNullOrEmpty({node.Left})")
                .WithTriviaFrom(node);
        }

        if (!Applied && AtLine(node) && _ruleId == "cs.string_concat_interpolate"
            && node.IsKind(SyntaxKind.AddExpression))
        {
            var interpolated = BuildInterp(node);
            if (interpolated != null)
            {
                Applied = true;
                return SyntaxFactory.ParseExpression(interpolated).WithTriviaFrom(node);
            }
        }

        return base.VisitBinaryExpression(node);
    }

    public override SyntaxNode? VisitMethodDeclaration(MethodDeclarationSyntax node)
    {
        if (!Applied && AtLine(node.ReturnType) && _ruleId == "cs.asynctask_return"
            && !node.Modifiers.Any(SyntaxKind.AsyncKeyword))
        {
            Applied = true;
            return node.WithModifiers(node.Modifiers.Add(SyntaxFactory.Token(SyntaxKind.AsyncKeyword)
                .WithTrailingTrivia(SyntaxFactory.Space)));
        }

        return base.VisitMethodDeclaration(node);
    }

    public override SyntaxNode? VisitClassDeclaration(ClassDeclarationSyntax node)
    {
        if (!Applied && AtLine(node) && _ruleId == "cs.record_dto")
        {
            var props = node.Members.OfType<PropertyDeclarationSyntax>().ToList();
            if (props.Count > 0)
            {
                var paramList = SyntaxFactory.ParameterList(
                    SyntaxFactory.SeparatedList(props.Select(p =>
                        SyntaxFactory.Parameter(p.Identifier)
                            .WithType(p.Type))));
                var record = SyntaxFactory.RecordDeclaration(
                        SyntaxFactory.Token(SyntaxKind.RecordKeyword), node.Identifier)
                    .WithModifiers(node.Modifiers)
                    .WithParameterList(paramList)
                    .WithSemicolonToken(SyntaxFactory.Token(SyntaxKind.SemicolonToken));
                Applied = true;
                return record.WithTriviaFrom(node);
            }
        }

        return base.VisitClassDeclaration(node);
    }

    private static string? BuildInterp(BinaryExpressionSyntax node)
    {
        var parts = new List<(bool isLiteral, string text)>();
        if (!Flatten(node, parts) || parts.Count < 2) return null;
        var sb = new StringBuilder("$\"");
        foreach (var (isLiteral, text) in parts)
        {
            if (isLiteral) sb.Append(text.Replace("\"", "\\\"", StringComparison.Ordinal));
            else sb.Append('{').Append(text).Append('}');
        }

        sb.Append('"');
        return sb.ToString();
    }

    private static bool Flatten(ExpressionSyntax expr, List<(bool isLiteral, string text)> parts)
    {
        if (expr is BinaryExpressionSyntax bin && bin.IsKind(SyntaxKind.AddExpression))
            return Flatten(bin.Left, parts) && Flatten(bin.Right, parts);
        if (expr is LiteralExpressionSyntax lit && lit.IsKind(SyntaxKind.StringLiteralExpression))
        {
            parts.Add((true, lit.Token.ValueText));
            return true;
        }

        if (expr is IdentifierNameSyntax or MemberAccessExpressionSyntax or ElementAccessExpressionSyntax
            or InvocationExpressionSyntax)
        {
            parts.Add((false, expr.ToString()));
            return true;
        }

        return false;
    }
}
