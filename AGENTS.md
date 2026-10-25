# Rules for every agent working on Wayneedle

Read `docs/ARCHITECTURE.md` first. It defines package ownership and the shared contracts.

## Hard device rule (non-negotiable)

**Do NOT run `adb`, install an APK, run instrumentation tests, use an emulator/AVD, Firebase Test Lab, or any
device/cloud testing.** Only the lead agent touches hardware, and only a verified Google Pixel 6 Pro
(`ro.product.device == raven`). Other phones may be attached to this machine; they are off limits.
You may compile, run JVM unit tests, run lint, and write instrumentation test *source* (the lead executes it).

## Language and style

* 100 % Kotlin. No Java source files, ever. Gradle Kotlin DSL only.
* Kotlin official code style, 4-space indent, trailing commas. Match the surrounding code's comment density:
  KDoc on public API and on non-obvious thresholds/decisions; no narration comments.
* Warnings are errors (`allWarningsAsErrors`, lint `warningsAsErrors`). Fix, don't suppress, unless justified in a comment.
* Do not edit `*.gradle.kts`, `gradle/**`, `AndroidManifest.xml`, or `domain/**` (lead-owned). Request changes in your report.
* No new dependencies: everything needed is already in `gradle/libs.versions.toml`. If something is missing, say so.
* No network, analytics, crash reporters, or INTERNET permission. Ever.

## Building and testing (memory is limited on this machine — be frugal)

* Always use the wrapper with `--no-daemon`:
  `./gradlew --no-daemon :app:compileDebugKotlin`, `./gradlew --no-daemon :app:testDebugUnitTest`.
* Do not run several Gradle builds at once from the same worktree; avoid unnecessary full `assemble` runs.
* A change is not done until `:app:compileDebugKotlin` and `:app:testDebugUnitTest` pass in your worktree.

## Git

* Work only in your own worktree/branch. Commit your work there with clear messages
  (imperative mood, e.g. `Add speed fix acceptance filter`). Do not push. Do not rewrite history.
* Commits are authored by the configured git user. **Never** add `Co-Authored-By` trailers, tool/assistant
  attribution, "Generated with ..." footers, or any mention of AI tools in commits, code, comments or docs.
* Do not touch files outside your ownership (see the table in `docs/ARCHITECTURE.md`).

## Final report (required)

End with: files created/changed, tests added and their results (paste the pass/fail summary),
key decisions and thresholds, contract changes you need from the lead, known problems. Do not claim anything
you did not run.
