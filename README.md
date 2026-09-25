# Brewlint

Static analysis for Spring Boot anti-patterns. Curated rules with a Java parser, not regex.

The bugs Brewlint looks for share a shape: **the code looks right, compiles, deploys, and then
quietly does nothing.** `@Transactional` on a `private` method is accepted by the compiler, accepted
by the container, and never opens a transaction. An unclosed `InputStream` is fine until the process
runs out of file descriptors under load, in a completely different part of the system. Neither
throws, neither logs, and neither is caught by a code review that reads for intent rather than
mechanics.

> **Status: Hitos 1 and 2 complete.** Eight rules, CLI with terminal and JSON output, 260 tests.
> See [Roadmap](#roadmap) for what is next.

---

## Quick start

```bash
git clone https://github.com/JUXCHXX/brewlint
cd brewlint
./mvnw package                      # no Maven required, the wrapper is committed
java -jar brewlint-cli/target/brewlint-cli-0.1.0-SNAPSHOT.jar scan --path fixtures
```

Only a JDK 17+ is required to build. Installing Brewlint as a single command is Hito 3.

```bash
./mvnw test                         # 260 tests
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

## Architecture

```
brewlint/
├── brewlint-core/     Parser, rule engine, plugin contract, finding model, config, project index.
│                      No terminal, no PDF, no CLI. Reused by every consumer.
├── brewlint-ai/       Placeholder for Hito 4. Anthropic + Ollama, opt-in.
├── brewlint-report/   ReportRenderer contract + terminal + JSON.
├── brewlint-cli/      picocli, exit codes, produces the fat jar.
└── fixtures/          A deliberately broken Spring project. NOT a Maven module:
                       it must never compile.
```

`fixtures/` is excluded from the Maven reactor on purpose. It holds code that is broken by design,
and keeping it out of `<modules>` means nothing ever tries to compile it and no test can
accidentally depend on it building.

## Roadmap

| Hito | Scope | Status |
|---|---|---|
| 1 | Scaffolding, rule engine, `AOP001`, `RES001`, CLI, terminal report, CI | **done** |
| 2 | `TX002`, `TX003`, `RES002`, `BEAN001`-`003`, shared `AopProxyability`, JSON output, project index | **done** |
| 3 | `jlink` runtime per platform, npm wrapper, `npm install -g brewlint` with no Java needed | next |
| 4 | `AiProvider` interface, Anthropic, local Ollama, secret redaction | planned |
| 5 | PDF report via OpenHTMLtoPDF | planned |
| 6 | VS Code extension rendering findings as Diagnostics | planned |
| 7 | GitHub Action commenting on pull requests | planned |
| 8 | N+1 detection, scoped to the unambiguous pattern | planned |

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
