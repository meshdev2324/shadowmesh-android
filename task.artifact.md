# Task List - Big Tech Senior Refactor & IDE Fix

- [x] **Phase 1: ViewModel Refactor (Big Tech Standards)**
    - [x] Refactor `observeManagers` for explicit type safety and MVI consistency
    - [x] Add comprehensive KDoc for public API surface
    - [x] Remove dead code (`TAG`, unused functions) and resolve all lint warnings
    - [x] Fix `resume`/`pause` signature ambiguity
- [x] **Phase 2: Documentation & Sync**
    - [x] Run `./gradlew dokkaHtml` to verify documentation generation
- [x] **Phase 3: Verification**
    - [x] Run `./gradlew lintDebug detekt ktlintCheck`
    - [x] Run `./gradlew :app:testDebugUnitTest`
    - [x] Final check of `analyze_file` output
