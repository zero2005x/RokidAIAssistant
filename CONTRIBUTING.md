# Contributing to RokidAIAssistant

Thanks for your interest. This project aims to be a clear, working reference architecture for AI on wearable displays: the phone does the thinking, the glasses handle display and input. Contributions that make that architecture easier to understand, test or reuse are the most welcome.

## Before you start

- **Bugs and small fixes:** open a pull request directly, or an issue first if you are unsure.
- **Features and design changes:** open an issue first so we can agree on the approach. Larger decisions are recorded as ADRs in `doc/adr/`.
- **New AI or STT providers:** provider breadth is currently frozen. Model updates go through the data-driven catalog (`phone-app/.../ai/catalog/`). Please don't open PRs that add new providers; improvements to existing ones are welcome.
- **Experimental areas:** Jev, Laya and the Agents design are experimental and not under active development.

## Development setup

See [README.md](README.md) for build requirements (JDK 21, Android SDK 36) and `local.properties`. You can build and run all unit tests without any Rokid credentials:

```bash
./gradlew :common:testDebugUnitTest :phone-app:testGithubDebugUnitTest :phone-app:testPlayDebugUnitTest :glasses-app:testDebugUnitTest
```

A real glasses connection needs your own `ROKID_CLIENT_SECRET`. Never commit keys, `local.properties` or `sn_auth_file.*` files.

## Pull requests

- Keep each PR focused on one change, with tests for new behavior.
- Run the unit tests above before pushing.
- Phone and glasses apps are updated independently, so protocol changes in `common/protocol/` must stay backward compatible: advertise new capabilities through the handshake and keep the old message path as a fallback.
- Write documentation in English. A Traditional Chinese translation under `doc/zh-TW/` is welcome but optional.

## Developer Certificate of Origin (DCO)

This project uses the [Developer Certificate of Origin 1.1](https://developercertificate.org/) instead of a CLA. By signing off a commit you certify that you wrote it, or otherwise have the right to submit it under the project's license.

Every commit must include a `Signed-off-by` line with your real name and email:

```
Signed-off-by: Your Name <you@example.com>
```

Git adds it for you with the `-s` flag:

```bash
git commit -s -m "fix(glasses): keep page 1 when new text arrives"
```

If you forgot, amend the last commit with `git commit --amend -s --no-edit`, or sign off a whole branch with `git rebase --signoff main`.

## License

By contributing, you agree that your contributions are licensed under the [Apache License, Version 2.0](LICENSE).
