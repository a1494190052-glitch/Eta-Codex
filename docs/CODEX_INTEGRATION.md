# Eta × ChatGPT Codex subscription integration

This fork keeps Eta as the Agent runtime and adds a **ChatGPT/Codex subscription** provider. It does not copy Miffan's chat UI, conversation database, or tool executor.

## Integration baseline

- Eta `main`: `eb8802a35f31079edaf9d46f15b29142c6e97f3e`
- Miffan `master`: `4fd12969b02671d9b6153e26ba4901c6ef1891b3`

## What is included

- Built-in **ChatGPT Codex** provider using the official Codex endpoint.
- Device-code sign-in flow: browser authorization, authorization-code exchange, refresh-token renewal, sign-out.
- Android Keystore AES-GCM storage for Codex credentials.
- Credentials are not placed in Room, backups, provider JSON, RemotePreferences, Agent IPC payloads, prompts, tool calls, or transcripts.
- Official Codex `/models` sync after sign-in, including the required `client_version` query parameter.
- Responses API streaming, reasoning continuation items, function/tool call streaming, multiple tool calls, tool output continuation, cancellation, and one retry after a 401-triggered token refresh.
- Eta remains responsible for JSON-schema validation, serial local tool execution, screenshots, terminal, browser, MCP, sensitive-result handling, and transcript persistence. Server-side web search is intentionally disabled for this provider so the Agent uses Eta's local tool catalog.

## First-use steps

1. Install a build of this fork.
2. Open **设置 → 模型 Provider → ChatGPT Codex**.
3. Tap **登录 ChatGPT / Codex**.
4. Eta opens the device authorization page. Sign in to the ChatGPT account that has Codex access and enter the displayed code.
5. After authorization, Eta refreshes the model list and selects the provider if a usable Agent model is returned.
6. Start a new Agent task. Eta sends local tools through the Responses API as function tools and continues with the returned tool outputs.

If model synchronization fails after a successful login, open the provider's **模型** tab and tap the remote-model refresh action.

## Security boundaries

- The provider accepts the subscription flow only for `https://chatgpt.com/backend-api/codex`; it cannot be redirected to a third-party endpoint.
- Provider custom headers cannot override `Authorization`, `ChatGPT-Account-Id`, or `originator` in Codex requests.
- The refresh token is encrypted with an Android Keystore key and stays in Eta's own application process. The Agent Runtime service is also hosted by Eta, so a hook process receives only a provider descriptor with an empty API key—not the subscription token.
- Sign out deletes the stored credentials. Copying or restoring a provider does not copy credentials, so the copied/restored provider must be signed in again.

## Build

Use Eta's documented Android build environment (its current project requires the Android SDK and the JDK/toolchain declared by Eta):

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

### Build on GitHub Actions (no PAT required)

`.github/workflows/build.yml` is included. To use it:

1. Create a **private** repository in your own GitHub account.
2. Push this source tree to it:

   ```bash
   git init
   git add .
   git commit -m "Eta Codex subscription integration"
   git branch -M main
   git remote add origin https://github.com/<你的用户名>/<私有仓库>.git
   git push -u origin main
   ```

3. Open the repository → **Actions**. The `Build Eta Codex fork` workflow runs automatically (or trigger it with *Run workflow*).
4. Download `eta-codex-debug-apk` from the run's artifacts. The APK is debug-signed and installable on the device.

GitHub Actions authenticates with its built-in `GITHUB_TOKEN`; no personal access token is needed. If you push over HTTPS, log in with your own account and use a scoped token (`repo` permission on that one repository, or SSH).

### Release APK and signing

The same workflow also runs `:app:assembleRelease` with R8 minification and produces `eta-codex-release-apk`. Signing works in two ways:

- **With your own key (recommended):** add repository secrets
  `RELEASE_KEYSTORE_BASE64` (base64 of the keystore file), `RELEASE_STORE_PASSWORD`,
  `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`. The workflow signs with that key and does not upload it.
- **Without secrets:** the workflow generates a keystore using the documented default alias/password and uploads it as the `eta-codex-release-keystore` artifact.

Important:

- **Keep the keystore.** Future updates must be signed with the same key; otherwise Android requires uninstalling the old version first.
- The fork's signature differs from the official Eta release, so uninstall the official APK before installing this one (and vice versa).
- If the minified release crashes while the debug APK works, R8 stripping is the first suspect; report the stack trace and the proguard mapping will be checked.



## Attribution and license note

The Codex device-flow behavior and request contract were adapted from the open-source Miffan project by Ayuilos (commit `4fd12969`). The adapted files (`CodexAuthRepository.kt`, `CodexRequestAuthenticator.kt`) carry AGPL-3.0 headers and are provided under AGPL-3.0.

The rest of this repository is a fork of Eta by Mangi-11 (commit `eb8802a3`) and is distributed under **PolyForm Noncommercial License 1.0.0** — source-available, **non-commercial only**. Do not sell, charge for, or commercially redistribute this project, and do not use it commercially, without permission from the upstream authors.
