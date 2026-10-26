# ADR 0009: Apache-2.0 + DCO

- **Status:** Accepted
- **Date:** 2026-10-08

## Context

This project is maintained as an open reference architecture for AI on wearable displays (`:phone-app`, `:glasses-app`, `:common`). Without an explicit open-source license and contribution provenance model, outside developers cannot safely reuse the protocol, catalog, routing, or pagination code, and maintainers lack a standard legal framework for accepting pull requests.

At the same time, two project-specific constraints apply:

1. The optional Rokid CXR SDK (`com.rokid.cxr:client-m`) is proprietary software downloaded from Rokid's Maven repository at build time and governed by Rokid's own terms; device credentials (`ROKID_CLIENT_SECRET`, `sn_auth_file.*`) cannot be distributed in the repository.
2. Requiring a Contributor License Agreement (CLA) adds administrative overhead and deters small fixes.

## Decision

1. **Apache License 2.0 (`LICENSE` & `NOTICE`):**
   - All source code and documentation in this repository are licensed under the [Apache License, Version 2.0](../../LICENSE).
   - The [NOTICE](../../NOTICE) file attributes the project and explicitly states that the Rokid CXR SDK is a separate, proprietary build-time dependency not covered by the Apache-2.0 license, and that Rokid credentials are never included in the repository. (In `:phone-app`, the CXR SDK is further isolated to the `github` product flavor so the `play` flavor compiles with zero proprietary SDK dependencies.)
2. **Developer Certificate of Origin (`CONTRIBUTING.md`):**
   - Instead of a CLA, all contributions are accepted under the [Developer Certificate of Origin (DCO) 1.1](https://developercertificate.org/).
   - Every commit must carry a `Signed-off-by: Name <email>` trailer (`git commit -s`), certifying that the contributor has the right to submit the work under the repository's Apache-2.0 license.

## Consequences

- Downstream developers and researchers can reuse, modify, and redistribute the codebase under standard Apache-2.0 terms (including its explicit patent grant).
- Contributors can sign off commits directly in Git (`git commit -s`) without external CLA tooling or paperwork.
- The boundary between open-source project code and proprietary Rokid SDK artifacts/credentials is explicitly documented for both users and distributors.
