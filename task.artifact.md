# Stage 3 VPN Stability Tasks

- [x] Fix GoBackend permission issue via `ContextWrapper` and deep static field patching in `CoreModule.kt`
- [x] Implement traffic flow verification (Handshake verification) in `ConnectUseCase.kt`
- [x] Add stale socket cleanup in `ShadowMeshApplication.kt`
- [/] Build and Install updated APK (using sub-agent task tool)
- [ ] Verify connection escalation (Normal -> Fragmented -> Reality)
- [ ] Verify `0 B` traffic issue is resolved in Myanmar
