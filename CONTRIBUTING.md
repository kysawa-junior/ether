# Contributing to Ether

## Before you start

- Read [README.md](README.md) and the documents in `docs/architecture/`.
- Check the roadmap. Phase 1 and the Phase 2 rendering foundation are complete; real GPU backends
  are next.
- For a substantial change, open an issue describing the problem, the proposed design, and the
  platform impact before writing code.

## Ground rules

1. **No fake implementations.** Do not add empty modules, stub backends, or TODO-driven public API.
   A feature is done when it works, is tested, and is documented.
2. **No unvalidated support claims.** Do not describe a platform or backend as supported unless CI
   builds it and a test runs it. Use "planned", "compiles only", or "untested" otherwise.
3. **Every behavior change has a test.** Bug fixes include a regression test.
4. **Public API is documented.** `explicitApi: strict` is enforced; KDoc is required for public
   declarations.
5. **Respect the layering.** `ether-math` is a leaf; `ether-core` does not depend on rendering or
   platform code; `ether-render-api` stays backend-neutral; `ether-render-core` owns validation,
   handle tables, and command translation; backends implement the HAL and leak no native objects.

## Development setup

```bash
./kotlin build
./kotlin test
```

See [docs/development/BUILDING.md](docs/development/BUILDING.md) for prerequisites and platform
notes. The build uses Amper, not Gradle. Do not add Gradle files.

## Code style

- Kotlin official style; 4-space indentation; trailing commas in multi-line declarations.
- Public declarations use explicit visibility and explicit return types.
- Value types (`data class`, immutable classes) by default. Mutable state must have a clear owner.
- Prefer small interfaces and composition over inheritance. `Engine` is open only because
  applications extend its lifecycle hooks.
- Common code must be platform-agnostic. Put platform behavior behind `expect`/`actual`.
- No JVM-only APIs in `src/` (for example `System.currentTimeMillis`). Use multiplatform APIs such
  as `kotlin.time.Clock`.
- Keep the hot path allocation-conscious; this matters more once the renderer exists.
- Comments explain *why*, not *what*. Delete commented-out code.

### Naming

- Packages: `dev.instrum.ether.<module>`.
- Modules: `ether-<area>`.
- Tests: descriptive behavior names (`cancellingDuringDispatchDoesNotDisturbIteration`).
- Handles/types: `GraphicsDevice`, `TextureDescriptor`, `CommandEncoder`, not abbreviations.

## Adding a module

A module is allowed only when it contains real implementation and tests:

1. Create `modules/ether-<name>/module.yaml`.
2. Add it to the root `project.yaml`.
3. Declare the target matrix and `explicitApi: strict`.
4. Add publishing metadata (`group`, `artifactId`, `version`).
5. Add the module to the architecture document and explain its dependency direction.
6. Add tests and a README section if it is user-facing.

Do not create placeholder modules for future features.

## Adding a platform

1. Add the target to `module.yaml` for every module.
2. Add `src@<target>/` actuals for every `expect` declaration.
3. Build the target locally or in CI.
4. Add a runtime test or mark the target "compiles only".
5. Update [docs/architecture/MULTIPLATFORM_STRATEGY.md](docs/architecture/MULTIPLATFORM_STRATEGY.md).

Apple targets require macOS/Xcode. Do not claim iOS support from a Linux build.

## Adding a rendering backend

The null backend exists and implements the full HAL. A real backend (Vulkan, WebGL2, Metal,
WebGPU) must:

1. Implement the backend HAL, not the public API. The HAL receives backend-token `BackendCommand`
   lists; the core translates portable handles.
2. Report capabilities truthfully; do not report a feature you cannot execute.
3. Return a unique submission token per `submit` and answer `isSubmissionComplete` truthfully and
   monotonically. The core's deferred free depends on it.
4. Pass the null-backend validation suite and the backend smoke test.
5. Add a CI job that runs the smoke test on the target platform.
6. Update the backend matrix and [ADR-0017](docs/architecture/ARCHITECTURE_DECISIONS.md).
7. Document known limitations and workarounds.

## Tests

- Put tests in `modules/<module>/test/`.
- Use `kotlin.test`; JVM maps to JUnit 5.
- Use `FakeTimeSource`, `RecordingLogSink`, and the float assertions from `ether-testing`.
- No real sleeps, wall-clock reads, network, or GPU in unit tests.
- Run `./kotlin test` before opening a PR.

See [docs/development/TESTING.md](docs/development/TESTING.md) for the full policy and the verified
test matrix.

## Commits and pull requests

- Use [Conventional Commits](https://www.conventionalcommits.org/): `feat:`, `fix:`, `test:`,
  `docs:`, `refactor:`, `build:`, `chore:`.
- Keep commits focused. Separate refactoring from behavior changes.
- PR description: problem, approach, tests run, platform impact, known limitations.
- Do not mix generated files with source changes.
- Do not commit `build/` or local toolchain caches.

Example:

```
feat(core): add engine lifecycle state machine

Adds Engine with Created/Initializing/Running/Paused/Stopping/Stopped/Failed
transitions, frame timing, and lifecycle hooks. Includes tests for invalid
transitions and delta clamping.
```

## Documentation

- Architecture changes require an ADR in `docs/architecture/ARCHITECTURE_DECISIONS.md`.
- User-facing behavior changes require README or guide updates.
- Release notes go in `CHANGELOG.md`.
- Keep claims traceable. If something is untested, say so.

## License

By contributing, you agree that your contributions are licensed under the Apache License 2.0. See
[LICENSE](LICENSE).
