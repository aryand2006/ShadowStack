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


## Packaging

| Image | Toolchains |
|-------|------------|
| `infra/docker/Dockerfile.api` | JVM only |
| `infra/docker/Dockerfile.api-enterprise` | python3/pip (LibCST), nodejs (Acorn), copies this tree to `/opt/shadowstack/native-engines`, sets `SHADOWSTACK_NATIVE_ENGINES`. Optional GnuCOBOL/`cobc` when available via apt (install is best-effort). |

`.NET` / Roslyn is **not** included in the enterprise image by default (SDK size). Use the host demo path or extend the Dockerfile. Point adapters at `SHADOWSTACK_NATIVE_ENGINES` when running in that image.
