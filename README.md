# Brewlint

[![CI](https://github.com/JUXCHXX/brewlint/actions/workflows/ci.yml/badge.svg)](https://github.com/JUXCHXX/brewlint/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

Static analysis for Spring Boot anti-patterns. Curated rules with a Java parser, not regex.

The bugs Brewlint looks for share a shape: **the code looks right, compiles, deploys, and then
quietly does nothing.** `@Transactional` on a `private` method is accepted by the compiler, accepted
by the container, and never opens a transaction. An unclosed `InputStream` is fine until the process
runs out of file descriptors under load, in a completely different part of the system. Neither
throws, neither logs, and neither is caught by a code review that reads for intent rather than
mechanics.

> **Status: Hitos 1 to 4 complete.** Eight rules, a self-contained binary, an optional AI pass with
> two providers, 354 Java tests and 26 packaging checks. See [Roadmap](#roadmap) for what is next.

---

## Quick start

```bash
npm install -g brewlint        # no Java needed
brewlint scan --path src
```

That is the whole install. The npm package ships a native launcher with a `jlink` runtime baked in,
about 48 MB per platform, so Brewlint runs on a machine that has never had a JDK on it.

Building from source needs a JDK 17+ and nothing else:

```bash
git clone https://github.com/JUXCHXX/brewlint
cd brewlint
./mvnw test                         # 260 tests
./mvnw package                      # runnable fat jar
java -jar brewlint-cli/target/brewlint-cli-0.1.0-SNAPSHOT.jar scan --path fixtures

./scripts/build-runtime.sh          # self-contained binary in dist/
node npm/scripts/test-install.mjs   # proves the install works with no Java
```

## What it reports

```
brewlint 0.1.0-SNAPSHOT

  src/main/java/com/example/broken/OrderService.java

    ERROR    17:5      AOP001
      @Transactional on a private method: Spring's proxy cannot intercept it, so the transaction is
      never started or committed.

      -> Make the method public and call it from another bean. If it must stay private, move the
      annotated method into its own Spring bean and delegate to it from the caller.

  src/main/java/com/example/broken/ReportExporter.java

    ERROR    16:21     RES001
      Local variable template holds a InputStream that is never closed.

      -> Use try-with-resources: try (InputStream template = ...) { ... }.

  ------------------------------------------------------------------------------
  26 findings in 6 files  ·  9 files scanned  ·  17 errors, 7 warnings, 2 info  ·  131ms
```

### Rules

| Id | Category | Severity | What it finds |
|---|---|---|---|
| `AOP001` | `spring-aop` | error | `@Transactional`, `@Async`, `@Cacheable`, `@CacheEvict`, `@Scheduled`, `@Retryable` on a `private`, `final` or `static` method, which CGLIB cannot override |
| `TX002` | `transactional` | error | A `@Transactional` method called from inside its own class, so the call bypasses the proxy |
| `TX003` | `transactional` | warning | `@Transactional` with no `rollbackFor`, where a checked exception can escape and Spring will commit instead of rolling back |
| `RES001` | `resource` | error | An `InputStream`, `Reader`, `Writer`, JDBC object, `ZipFile` or `Channel` assigned to a variable nothing closes |
| `RES002` | `resource` | warning | A stream from `Files.lines/list/walk/find` that is never closed. Consuming it is not closing it |
| `BEAN001` | `bean` | error | A JPA `@Entity` or Mongo `@Document` also annotated `@Component`/`@Service`/`@Repository` |
| `BEAN002` | `bean` | warning | A prototype-scoped bean injected into a singleton, so every caller shares one instance |
| `BEAN003` | `bean` | info | Field injection with `@Autowired`/`@Inject`/`@Resource`, which leaves a half-constructed bean |

`./mvnw package && java -jar brewlint-cli/target/*.jar scan --list-rules` prints the live list.

## Usage

```
brewlint scan [options]

  -p, --path <dir>        Directory or single .java file. Default: current directory.
  -c, --config <file>     Path to brewlint.yml. Default: <path>/brewlint.yml.
      --format <format>   terminal or json. Default: terminal.
  -o, --output <file>     Write the report to a file instead of standard output.
      --fail-on <sev>     Exit 1 at or above this severity: ERROR, WARNING, INFO, NONE.
      --max-findings <n>  Print at most n findings. The summary still counts them all.
      --no-color          Never emit ANSI escapes.
      --color             Always emit ANSI escapes, even when piped.
      --list-rules        Print every rule and exit.

  --ai <provider>         anthropic or ollama. Off by default. See "The optional AI pass".
      --ai-model <id>     Model id for --ai.
      --ai-max-findings <n>   How many findings to ask about. Default: 50.
      --ai-max-lines <n>       How many lines of code to send. Default: 1200.
```

Exit codes are a contract with CI:

| Code | Meaning |
|---|---|
| `0` | Clean, or findings below the threshold |
| `1` | Findings at or above `--fail-on` |
| `2` | Brewlint could not run: bad path, malformed `brewlint.yml`, unknown rule id |

### JSON output

`--format json` is the contract Hito 6 (VS Code) and Hito 7 (GitHub Action) will be written against,
which makes it the most consequential format in the project. Every key is always present, even when
its value is null, so a client never has to tell "absent" from "empty".

```console
$ brewlint scan --path src --format json --output report.json
$ python3 -c "import json; print(len(json.load(open('report.json'))['findings']))"
3
```

```json
{
  "schemaVersion": 1,
  "tool": "brewlint",
  "toolVersion": "0.1.0-SNAPSHOT",
  "filesScanned": 9,
  "filesWithParseErrors": 0,
  "durationMillis": 131,
  "counts": { "error": 17, "warning": 7, "info": 2 },
  "findings": [
    {
      "ruleId": "RES001",
      "category": "resource",
      "severity": "ERROR",
      "file": "src/main/java/com/example/broken/InventoryDao.java",
      "line": 18,
      "column": 20,
      "endLine": 18,
      "message": "Local variable connection holds a Connection that is never closed.",
      "suggestion": "Use try-with-resources: try (Connection connection = ...) { ... }."
    }
  ]
}
```

JSON is never truncated by `--max-findings`. A client reading a partial array would have to guess
whether there were no more findings or whether the output was cut off, which is exactly the
ambiguity that makes a machine-readable format untrustworthy.

Every finding carries a `source` of `RULE` or `AI`, so a consumer can always tell a proven finding
from a suggested one. "Fix everything at ERROR" is a different instruction depending on which it
is.

## The optional AI pass

Curated rules catch what the syntax tree can prove. They cannot catch that a `@Transactional`
boundary is in the wrong place *for this codebase*, or that a cache is invalidated somewhere the
rules do not look. A language model can read the code and say so. That is the whole of the feature,
and it is strictly additive.

```bash
brewlint scan --path src                                  # rules only, always complete
ANTHROPIC_API_KEY=... brewlint scan --path src --ai anthropic
ollama serve && brewlint scan --path src --ai ollama      # nothing leaves the machine
```

**It is never required.** `brewlint-core` does not depend on `brewlint-ai`, and there is a test
that runs the engine with the AI module off the classpath entirely. A provider that is missing,
unconfigured or unreachable produces a warning on stderr and the complete rule report, with the
exit code the rules alone would have produced. If a scan produced different findings depending on
whether a model was reachable, the report would be lying about how much the rules found.

**It never outranks the rules.** A rule can justify an `ERROR` because it decided something. A
model claiming an `ERROR` is a model claiming to have decided something, so every AI finding is
capped at `WARNING` and marked `AI001`. A suggestion for a file that was not part of the scan is
dropped, because a report that points at a line which does not exist is worse than saying nothing.

**Nothing leaves silently.** `--ai` must be named, the key comes from `ANTHROPIC_API_KEY` and is
never written to disk, and every byte of code goes through `SecretRedactor` first. Redaction
removes PEM blocks, cloud and provider keys, JWTs, tokens with recognisable prefixes, credentials
embedded in connection URLs, and a generic layer for `password = "..."` style assignments. It keeps
the name and the scheme, so the report still says a secret was on that line without saying what it
was.

What redaction cannot do is find a secret shaped like nothing in particular, a password typed as a
bare string literal in the middle of a method. That is why `--ai ollama` is a first-class option
rather than a fallback: for code that cannot leave, "nothing leaves the machine" is a guarantee and
"we redacted the things we recognise" is not.

**Both providers ask the same question.** `ReviewPrompt` is shared, and a test asserts the two
providers receive a byte-identical prompt, so comparing results across machines compares the model
rather than the wording.

**Bounded by construction.** `maxFindings` and `maxExcerptLines` are part of `AiRequest`, not
settings, so a provider cannot ignore them. The most common way an optional AI feature goes wrong
is not a security problem, it is a cost one.

Progress messages go to **stderr**, always. Writing them to stdout would corrupt `--format json`,
and a report that is syntactically invalid because of a status line breaks the consumer silently.

The transport is an interface, so every provider test runs with a stub and no network, no API key
and no bill. Not covered by tests: a live call to either API.

## Configuration

`brewlint.yml` in the project root. Every key is optional; a missing file means defaults, which is
what makes Brewlint useful on a project it has never seen.

```yaml
rules:
  AOP001: true                       # boolean toggles a rule
  RES001:
    enabled: true
    severity: WARNING                # ERROR (default) | WARNING | INFO

exclude:
  - "**/generated/**"                # globs: **, *, ?
  - "**/Legacy*.java"
```

Configuration is read with SnakeYAML's `SafeConstructor`. A repository whose pull requests can edit
`brewlint.yml` gets that file parsed by this code, so it must never be able to instantiate arbitrary
classes. A misspelled rule id or severity is a hard error, not a silent no-op.

## Design decisions

The parts that took thought, and why.

**A rule is a plugin, not a subclass.** `Rule` is a plain interface. Discovery is `ServiceLoader`,
so adding a rule means adding a class and one line to
`META-INF/services/io.github.brewlint.core.rule.Rule`. No engine change, no registry edit, no
`@Component` scan. That is what keeps the rule set from being welded to the engine.

**Rules never construct a `Finding`.** They report through a `RuleCollector`, and the engine stamps
the rule id, category and effective severity. A rule therefore cannot misreport its own identity,
and `brewlint.yml` severity overrides apply in exactly one place instead of in every rule.

**`TypeSolver` is an interface for a reason.** Hito 1 ships `SyntacticTypeSolver`: it reads the
type name as written in the source and resolves it against a table of JDK hierarchies, with no
classpath and no `JavaSymbolSolver`. That is what lets a linter start instantly. The cost is that
`class CsvSource extends InputStream` in your project is not recognised. Because rules are written
against the interface and not the strategy, swapping in a symbol solver later changes no rule.

**The type table is asserted against the real JDK.** Every inheritance edge in it is checked with
reflection in `SyntacticTypeSolverTest`. This is not ceremony: it caught two wrong edges during
development — `java.sql` types implement `AutoCloseable`, not `Closeable`, and `SocketChannel`
implements `ByteChannel`, not `SeekableByteChannel`. Either one would have silently weakened
RES001 with no test failing.

**A rule that throws is disabled, not fatal.** A file that will not parse is counted and the scan
continues. Not everything on a real codebase compiles, and a linter that stops at the first odd
file is useless.

**Cross-file rules are opt-in, because they cost a second parse.** "A prototype-scoped bean is
injected into a singleton" is a statement about two different files, and a rule that only sees one
`CompilationUnit` cannot make it. A rule declares `requiresProjectIndex()` and the engine builds a
small index of every declared type if, and only if, an enabled rule asks. A run of single-file rules
pays nothing. `RuleRegistryTest` asserts that exactly one of the eight rules currently opts in, so
the cost cannot quietly spread.

**Names are not proof of identity.** The index is keyed by simple name, and two packages can both
declare an `Order`. So `BEAN002` only reports when *every* type answering to that name is prototype
scoped. One `com.a.Order` being a prototype must not implicate an unrelated `com.b.Order`.

**False positives are the expensive failure.** A missed issue costs a developer a little time. A
false positive costs them trust in every other finding, and then they turn the tool off. Every rule
ships with negative tests for the correct code next to it: try-with-resources, manual
`try/finally`, method parameters, package-private methods, `rollbackFor` already present, entity
with no stereotype, and a deliberately correct fixture package that must stay clean forever.

**Some things are reported, some are only suggested.** `BEAN003` field injection has default
severity INFO, because nothing breaks today and conflating a convention with a defect is how people
come to hate a linter. `BEAN001` is an ERROR, because component scanning really does create a second
instance. Same rule family, different honesty about how bad it is.

**Engine knows nothing about output.** `brewlint-core` takes paths in and returns an
`AnalysisResult`. The terminal renderer, the JSON the VS Code extension will consume, the PDF and
the GitHub Action are all built on that one object.

### Three things that are easy to get wrong, and were

`maven-shade-plugin` silently loses `META-INF/services` unless `ServicesResourceTransformer` is
configured. Miss it and the fat jar discovers **zero** rules, reports "No findings" and exits `0`.
CI runs the shaded jar against `fixtures/`, asserts all eight rule ids appear, and separately
asserts the correct fixtures stay clean.

`brewlint.yml` cannot carry a `distributionSha256Sum` for the Maven distribution. The wrapper
validates with `sha256sum -c` and looks for `sha256sum` before `shasum`; macOS ships
`/sbin/sha256sum`, a stub with no `-c` support, so validation can never pass on a Mac and
`./mvnw` aborts with "your Maven distribution might be compromised". The file explains the trade-off
in place.

A hand-rolled JSON writer has exactly one place where it can go quietly wrong: nesting. Writing
`"counts": <object>` through a method that quotes its value produces JSON that every parser accepts
and every consumer misreads. `JsonReportRendererTest` asserts the nested types are objects and
arrays, and CI re-parses the real output with `json.load` for the same reason.

Node's platform names and npm's are not the same. Node says `darwin-arm64`; the package that holds
the binary is called `@brewlint/macos-arm64`. Deriving one from the other gives a shim that looks for
a package the install never put there, which fails confusingly because the install genuinely
succeeded. `lib/platforms.js` is a data file rather than branching logic so the mapping is stated
once, and `npm/brewlint/test/platforms.test.js` pins it.

## Packaging

Three npm packages, one per platform, plus a thin main package:

```
npm install -g brewlint
└── optionalDependencies
    ├── @brewlint/macos-arm64   48 MB   (skipped on other platforms)
    ├── @brewlint/linux-x64     ~45 MB  (skipped)
    └── @brewlint/win-x64       ~45 MB  (skipped)
```

Each platform package declares `os` and `cpu`, which is the entire mechanism: npm reads them and
skips what cannot run. The main package depends on all three as **optional** dependencies, so a
platform nobody ships never fails the install.

The launcher is 3.8 kB of Node that resolves the platform package and `spawn`s the native binary
with inherited stdio. Two things it deliberately gets right:

- **It forwards the exit code exactly.** 0, 1 and 2 are a contract with CI. A launcher that
  collapsed them to 0 or 1 would turn a red build green and nobody would find out until a real bug
  shipped. `test-install.mjs` asserts all three survive.
- **It inherits stdio instead of piping.** The report asks whether stdout is a terminal to decide on
  colour, and a pipe between the shim and the binary would break that *and* swallow the exit status.

**jpackage cannot cross-compile.** A macOS machine produces a macOS binary and nothing else, so the
release workflow is a matrix over the three runners. `scripts/build-runtime.sh` refuses a target that
does not match the host with a clear message rather than failing later.

**The app image layout is discovered, not hardcoded.** macOS produces a `.app` bundle, Linux produces
a directory named after the app, Windows a directory with an `.exe` at its root. The build script
tries each candidate, prints the directory tree when none match, and writes the path it found to
`dist/launcher-<target>.txt`. The npm assembler reads that file and fails if it disagrees with the
table in `lib/platforms.js`, and a unit test asserts the same. Guessing the layout is how a build
goes green while producing a package with no binary in it, and the failure then lands on a user's
machine at the first run. This was found by CI on Linux, not by a local build, which is the argument
for having the matrix at all.

Two constraints the build hit, both now documented in the script:

- A macOS `CFBundleShortVersionString` must start at 1, so the bundle version (`1.0.0`) and the npm
  version (`0.1.0`) are separate variables.
- `jpackage` copies its whole `--input` directory into the image. Pointing it at the Maven `target/`
  dragged 4.6 MB of `test-classes` and `surefire-reports` into the tarball. The script stages one
  file into an empty directory first.

The runtime image is closed at build time, which is why `build-runtime.sh` asserts its four modules
are present and runs the launcher with an empty environment before declaring success. `java.net.http`
is in that list for Hito 4: without it the packaged binary would work perfectly until the Anthropic
provider shipped, and then fail only for npm users, which is the worst possible way to find out.

## Architecture

```
brewlint/
├── brewlint-core/     Parser, rule engine, plugin contract, finding model, config, project index.
│                      No terminal, no PDF, no CLI, and deliberately no AI. Reused by every consumer.
├── brewlint-ai/       Optional AI pass: AiProvider contract, Anthropic, Ollama, secret redaction.
├── brewlint-report/   ReportRenderer contract + terminal + JSON.
├── brewlint-cli/      picocli, exit codes, --ai wiring, produces the fat jar.
└── fixtures/          A deliberately broken Spring project. NOT a Maven module:
                       it must never compile.
```

`brewlint-ai` depends on `brewlint-core`; `brewlint-core` does not depend on `brewlint-ai`. That one
direction is what keeps the report complete when no provider is configured, and there is a test that
runs the engine with the AI module off the classpath entirely.

`fixtures/` is excluded from the Maven reactor on purpose. It holds code that is broken by design,
and keeping it out of `<modules>` means nothing ever tries to compile it and no test can
accidentally depend on it building.

## Roadmap

| Hito | Scope | Status |
|---|---|---|
| 1 | Scaffolding, rule engine, `AOP001`, `RES001`, CLI, terminal report, CI | **done** |
| 2 | `TX002`, `TX003`, `RES002`, `BEAN001`-`003`, shared `AopProxyability`, JSON output, project index | **done** |
| 3 | `jlink` runtime per platform, npm wrapper, install with no Java needed | **done, pending publish** |
| 4 | `AiProvider` contract, Anthropic, local Ollama, secret redaction | **done** |
| 5 | PDF report via OpenHTMLtoPDF | next |
| 6 | VS Code extension rendering findings as Diagnostics | planned |
| 7 | GitHub Action commenting on pull requests | planned |
| 8 | N+1 detection, scoped to the unambiguous pattern | planned |

Hito 3 is complete but not published. The three platform binaries cannot be built on one machine, so
the release workflow needs a run on the tag `v0.1.0`, and `npm publish --provenance` needs an
`NPM_TOKEN`. Until then the binary is not on the registry and `npm install -g brewlint` does not
resolve.

N+1 is deliberately last. It needs dataflow analysis between the method that loads the entity and
the one that iterates it, and a detector that guesses wrong there is worse than no detector.

Two properties are fixed now and must survive into Hito 4: **AI is never required**, so the report
is complete with no API key configured; and **nothing leaves the machine silently**.

## Contributing

`./mvnw test` must pass. A new rule needs:

1. A class implementing `Rule`, in its own package under `brewlint-core`.
2. One line in `META-INF/services/io.github.brewlint.core.rule.Rule`.
3. Tests with both the broken case and the correct case that must **not** fire.
4. A row in the rules table above.

The third point is the one that matters. A rule that cannot prove it stays quiet on correct code is
not ready.

## License

MIT. See [LICENSE](LICENSE).
