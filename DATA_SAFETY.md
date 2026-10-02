# ShadowMesh Android Data Safety Manifest

This document provides the information required for the Google Play Data Safety form. ShadowMesh is designed as a **Zero-PII** application, meaning we prioritize collecting the absolute minimum data required for functionality.

## 📋 Data Collection & Security

| Question | Answer |
|----------|--------|
| **Does your app collect or share any of the required user data types?** | **No** |
| **Is all of the user data collected by your app encrypted in transit?** | **Yes** (mTLS and AES-256) |
| **Do you provide a way for users to request that their data be deleted?** | **Yes** (Instant Panic Wipe + Session Revocation) |

## 🔍 Specific Data Categories

### 1. Location
- **Collected**: No
- **Shared**: No

### 2. Personal Info
- **Collected**: No (No Name, Email, Address, or Phone Number)
- **Shared**: No

### 3. Financial Info
- **Collected**: No
- **Shared**: No

### 4. Health & Fitness
- **Collected**: No
- **Shared**: No

### 5. Messages & Photos
- **Collected**: No
- **Shared**: No

### 6. App Activity
- **Collected**: Yes (Anonymous session heartbeats and version info for performance)
- **Shared**: No
- **Purpose**: App Functionality, Analytics (Anonymous)

### 7. Device or Other IDs
- **Collected**: Yes (Anonymous hardware-bound identifier rotated daily)
- **Shared**: No
- **Purpose**: App Functionality (Device activation limit enforcement)

---

**Note**: ShadowMesh uses a `VpnService` to route traffic. We do not inspect, log, or store any traffic data passing through the tunnel.
