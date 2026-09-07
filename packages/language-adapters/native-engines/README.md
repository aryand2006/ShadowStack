# ShadowStack native AST engines

Industry-parity parse/rewrite frontends invoked by the Java language adapters.

| Engine | Path | Runtime |
|--------|------|---------|
| Python LibCST | `../src/main/resources/com/shadowstack/adapters/python/ast_engine.py` | `pip install -r python/requirements.txt` |
| JavaScript Acorn | `js/ast_engine.mjs` | Node 18+ (deps vendored under `js/node_modules`) |
| C# Roslyn | `csharp/publish/CsharpAstEngine.dll` | `dotnet exec …/CsharpAstEngine.dll` (.NET 8) |

Rebuild C# publish:

```bash
export PATH="$HOME/.dotnet:$PATH"
dotnet publish csharp/CsharpAstEngine/CsharpAstEngine.csproj -c Release -o csharp/publish
```
