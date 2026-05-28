# Procedure for publishing to Maven Central

Publishing goes through the **Sonatype Central Publishing Portal**
([central.sonatype.com](https://central.sonatype.com)). The legacy OSSRH service
(`s01.oss.sonatype.org`) has been retired.

Mechanics: the `com.vanniktech.maven.publish` Gradle plugin (configured in `build.gradle`)
uploads all seven artifacts, signs them with the in-memory GPG key, and either pushes a
SNAPSHOT straight to the Central snapshot repo or creates a release deployment that waits
for a manual Publish click in the Portal UI. The upload runs in a GitHub Action
(`.github/workflows/maven_publish.yml`) using secrets stored in GitHub.

## Required GitHub secrets (one-time setup)

If you have never published from this repo before — or if you are still holding OSSRH
tokens — replace these secrets:

| Secret | What it is |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME`   | Central Portal user-token *username* (NOT your login). Generate at central.sonatype.com → View Account → Generate User Token. |
| `MAVEN_CENTRAL_PASSWORD`   | Central Portal user-token *password*. |
| `CENTRAL_SIGNINGKEYID`     | Short (8-hex) GPG key id. |
| `CENTRAL_SIGNINGKEY`       | ASCII-armored secret key: `gpg --export-secret-keys --armor <keyid>` — full BEGIN/END block. |
| `CENTRAL_SIGNINGPASSWORD`  | Passphrase for that key. |

OSSRH tokens will return `401` against the Portal; they are not compatible.

## Build version

Version identifier looks like this: `0.3.3+2023-04-25`

Change it in `build.gradle` (the `allprojects { version = '…' }` line). There are
currently no other places.

Commit it, commit message shall read:
```text
Release version 0.3.3+2023-04-25
```

## Tag

Copy and edit the following. Notice the "v" in both tag and description.
```shell
git tag -a v0.3.3+2023-04-25 -m "Release version v0.3.3+2023-04-25"
git push origin --tags
```

## Upload to Central Portal

Run the **Build and Publish to Maven Central** GitHub Action manually
(`workflow_dispatch`). It executes:

```sh
./gradlew clean build publishToMavenCentral
```

For a SNAPSHOT version (anything ending in `-SNAPSHOT`) the artifacts appear in
the Central snapshot repo immediately and you are done — skip the next section.

For a release version the artifacts land in a *staging deployment* in the Portal,
awaiting your review.

## Review and Publish (release versions only)

Log in to the Central Portal: [https://central.sonatype.com](https://central.sonatype.com)
→ **Publish** (or **Deployments**) tab.

* Find the new deployment for `com.storebrand.healthcheck`.
* Wait for status to reach **VALIDATED** (automatic validation runs on upload —
  there is no separate "Close" step like OSSRH had).
* Expand the deployment, verify the artifact list and the version are what you expect.
* Click **Publish**. **This is irrevocable** — once published it propagates to Maven
  Central within ~30 min and cannot be unpublished.

If validation fails, drop the deployment in the Portal, fix the issue, and re-run the
workflow.

> Tip: to skip the manual Publish click on future releases, change the workflow command
> to `publishAndReleaseToMavenCentral` — the plugin will poll for validation and Publish
> automatically.

## Back to SNAPSHOT

Increase the *revision* counter, remove the date, add `-SNAPSHOT`.

Commit.
