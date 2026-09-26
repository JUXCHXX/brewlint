# Brewlint for VS Code

Spring Boot anti-patterns as you type: `@Transactional` on a private method, a `Connection` that is
never closed, `@Autowired` on a field, and five more, as Diagnostics in the Problems panel.

No Java required. The extension depends on the [`brewlint`](https://www.npmjs.com/package/brewlint)
package, which carries its own runtime.

## What it reports

Every rule Brewlint has, with the rule id as the diagnostic code. That is what makes the ids worth
having: `brewlint.AOP001` filters in the Problems panel, and the id is a stable thing to write down
in a code review, an issue, or a commit message.

`@Async`, `@Cacheable` and `@Scheduled` are reported under `AOP001` rather than a transaction rule,
because the cause is the same: Spring's proxy cannot intercept them, and a method the proxy never
sees is a method that never runs.

A finding from a language model, if you ever run one, says `[suggested by a model, unverified]` in
the message. It never says it in a field you would have to go looking for.

## Settings

| Setting | Default | What it does |
|---|---|---|
| `brewlint.enabled` | `true` | Analyse the project and report findings. |
| `brewlint.debounce` | `500` | Milliseconds after a save before rescanning. |
| `brewlint.scanTimeout` | `120000` | Milliseconds before a scan is abandoned. |
| `brewlint.binaryPath` | `""` | Use a different binary instead of the bundled one. |

## Commands

- **Brewlint: Rescan** — analyse again now.
- **Brewlint: Show output** — the log, including which version ran and how long it took.

## Three decisions worth knowing about

**The extension is thin on purpose.** `src/report.js`, `src/diagnostics.js` and `src/scan.js` do
not import `vscode`, which means they are tested with `node --test` against the real binary in this
repository's own harness. `src/extension.js` is the only file that talks to the editor. That split
is why the exit-code contract, the line-number conversion and the JSON schema are all covered by
tests that run in a second, rather than by tests that need an editor to run.

**A failed scan does not clear the panel.** If Brewlint cannot run, the previous findings stay put
and the reason goes to the output channel and a status bar item. Replacing them with an empty
Problems panel would be the worst available failure: it tells you your code is clean at the exact
moment the tool stopped being able to tell.

**Whole-project problems are not diagnostics.** A missing binary, a scan that could not run, a file
that did not parse: none of these belong to a source line. Attaching them to an invented URI puts a
phantom file in your Problems panel, so they go where a project-level message can honestly live.

## Running the tests

```bash
npm install
npm test
```

The tests download the published `brewlint` package and run the real binary. There is no mock of the
process, because the exit codes are the part of the contract worth pinning down and a mock agrees
with whatever the test assumed.

That also means the suite fails if the published package breaks, which is the point: the extension's
premise is that installing `brewlint` gives you a working analyser, and only installing it can
check that.
