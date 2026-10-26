# Architecture Decision Records (ADRs)

This directory records the key architectural decisions of **Rokid AI Assistant** using Michael Nygard's format (*Context*, *Decision*, *Consequences*). Each record captures a single decision, the forces behind it, and the trade-offs it introduces.

## Index

| ADR | Title | Status |
| --- | ----- | ------ |
| [0001](0001-phone-is-the-brain.md) | Phone is the brain, glasses handle display and input | Accepted |
| [0002](0002-shared-binary-protocol-and-capability-handshake.md) | Shared binary protocol, extended through the capability handshake | Accepted |
| [0003](0003-provider-abstraction-and-data-driven-catalog.md) | Provider abstraction with a data-driven catalog; provider breadth frozen | Accepted |
| [0004](0004-decision-routing-before-generation.md) | Decision routing happens before generation, outside the AI session (3.5 s budget, fallback to primary) | Accepted |
| [0005](0005-layout-and-pagination-measured-on-glasses.md) | Layout belongs to the device: pagination is measured on the glasses | Accepted |
| [0006](0006-errors-never-shown-or-spoken-as-answers.md) | Errors are never shown or spoken as answers | Accepted |
| [0009](0009-apache-2-0-and-dco.md) | Apache-2.0 + DCO | Accepted |

> **Note:** Numbers `0007` (streaming to the glasses) and `0008` (surface routing) are reserved for upcoming proposals on the roadmap.

## Related Documentation

- [Project README](../../README.md)
- [Architecture Overview](../ARCHITECTURE.md)
- [Decision Routing & Glasses Display](../DECISION_ROUTING_AND_DISPLAY.md)
- [Contributing Guide](../../CONTRIBUTING.md)
