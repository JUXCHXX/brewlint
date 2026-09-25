# brewlint

Static analysis for Spring Boot anti-patterns. Curated rules with a Java parser, not regex.

The bugs it looks for share a shape: **the code looks right, compiles, deploys, and then quietly
does nothing.** `@Transactional` on a `private` method is accepted by the compiler, accepted by the
container, and never opens a transaction.

## Install

```bash
npm install -g brewlint
```

No Java required. The package carries a self-contained runtime, so it works on a machine that has
never had a JDK installed.

| Platform | Binary |
|---|---|
| macOS on Apple Silicon | `@brewlint/macos-arm64` |
| Linux on x86-64 | `@brewlint/linux-x64` |
| Windows on x86-64 | `@brewlint/win-x64` |

Those are optional dependencies: npm installs the one that matches your platform and skips the rest.
You never install them by hand.

## Use

```bash
brewlint scan
brewlint scan --path src/main/java
brewlint scan --format json --output report.json
brewlint scan --list-rules
```

| Option | Meaning |
|---|---|
| `-p, --path <dir>` | Directory or single `.java` file. Default: current directory |
| `--format <format>` | `terminal` or `json`. Default: `terminal` |
| `-o, --output <file>` | Write to a file instead of standard output |
| `--fail-on <severity>` | Exit 1 at or above `ERROR`, `WARNING`, `INFO` or `NONE` |
| `--no-color` / `--color` | Force ANSI escapes off or on |

Exit codes: `0` clean, `1` findings over the threshold, `2` could not run.

## Rules

`AOP001` proxy-dependent annotation on a non-proxyable method · `TX002` self-invoked transaction ·
`TX003` missing `rollbackFor` on a checked exception · `RES001` unclosed stream or JDBC resource ·
`RES002` unclosed `Files` stream · `BEAN001` entity also annotated as a bean · `BEAN002` prototype
bean in a singleton · `BEAN003` field injection

## Configure

A `brewlint.yml` in the project root. Every key is optional.

```yaml
rules:
  AOP001: true
  RES001:
    enabled: true
    severity: WARNING
exclude:
  - "**/generated/**"
```

## Programmatic use

```js
const { binaryPath } = require('brewlint');
```

Returns the absolute path to the binary, for tools that spawn it as a subprocess. This is the
entry point the VS Code extension uses.

## Links

- [Source, issues and the full rule reference](https://github.com/JUXCHXX/brewlint)
- [MIT licensed](https://github.com/JUXCHXX/brewlint/blob/main/LICENSE)
