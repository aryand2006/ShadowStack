# CsharpAstEngine

Roslyn-based C# modernization CLI used by `CsharpAdapter`.

## Build / publish

```bash
export PATH="$HOME/.dotnet:$PATH"
dotnet publish -c Release \
  -o packages/language-adapters/native-engines/csharp/publish \
  packages/language-adapters/native-engines/csharp/CsharpAstEngine/CsharpAstEngine.csproj
```

Published entrypoint:

```
packages/language-adapters/native-engines/csharp/publish/CsharpAstEngine.dll
```

## Usage

```bash
dotnet exec packages/language-adapters/native-engines/csharp/publish/CsharpAstEngine.dll \
  detect path/to/File.cs

dotnet exec packages/language-adapters/native-engines/csharp/publish/CsharpAstEngine.dll \
  apply path/to/File.cs <ruleId> <startLine>
```

Development (no publish):

```bash
dotnet run --project packages/language-adapters/native-engines/csharp/CsharpAstEngine -- detect File.cs
```
