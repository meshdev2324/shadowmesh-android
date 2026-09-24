# Implementation Plan - "Big Tech" Code Quality & IDE Health

This plan addresses the IDE unresolved reference issues and elevates the `VPNManagerViewModel` to "Big Tech" standards by improving type safety, reducing ambiguity for the IDE parser, and refining the MVI (Model-View-Intent) pattern.

## User Review Required

> [!NOTE]
> The "Unresolved reference" errors are likely IDE sync ghosts since the project builds successfully in Gradle. However, we will refactor the code to be more explicit, which often helps the IDE's static analysis engine resolve symbols correctly.

> [!IMPORTANT]
> We will transition to a more explicit state reduction pattern to avoid the `Unit` vs `VPNUiState` ambiguity that the IDE is reporting in the `when` blocks.

## Proposed Changes

### Presentation Layer (`app`)

#### [MODIFY] [VPNManagerViewModel.kt](file:///home/red/MyProject/shadowmesh/mobile-native/android/app/src/main/java/com/shadowmesh/app/VPNManagerViewModel.kt)
- **State Reduction Refactor**:
    - Refactor `observeManagers()` to use more explicit mapping.
    - Explicitly return the new state in `updateUiState` lambdas to resolve IDE type inference issues.
    - Use `with(uiState.value)` or explicit naming instead of nested `it` to improve readability and "Big Tech" standards.
- **Code Quality & Maintenance**:
    - Remove unused `TAG` and redundant functions like `dismissRootWarning` (or integrate them properly).
    - Add descriptive KDoc to public API surface.
    - Fix the `resume()` / `pause()` signature ambiguity by ensuring the compiler knows exactly which `ConnectionManager` is being used.
- **Type Safety**:
    - Be explicit with `ConnectionProgress` and `SessionSignal` types in `when` branches.
    - Add trailing commas and fix lint warnings identified by `analyze_file`.

### UI Components (`ui-kit`)

#### [MODIFY] [ShadowMeshComponents.kt](file:///home/red/MyProject/shadowmesh/mobile-native/android/ui-kit/src/main/java/com/shadowmesh/ui_kit/components/ShadowMeshComponents.kt)
- (Internal) Ensure consistency with the refined `VPNUiState` labels.

## Verification Plan

### Automated Tests
- Run `./gradlew :app:testDebugUnitTest` to ensure no regression in state handling.
- Run `./gradlew lintDebug` to verify all lint warnings are resolved.

### Manual Verification
- Verify in Android Studio that the red squiggles are gone after the refactor and a fresh sync.
- Verify that VPN connection toggles and settings persistence still function as expected.
