# Procedure for publishing to Maven Central

The build and publish to OSSRH Staging repos is done by a GitHub Action, employing
secrets stored in GitHub.

## Build version

Version identifier looks like this: "0.3.3+2023-04-25"

Change it in `build.gradle`. There are currently no other places.

Commit it, commit message shall read
```text
Release version 0.3.3+2023-04-25
```

## Tag

Copy and edit the following. Notice the "v" in both tag and description.
```shell
git tag -a v0.3.3+2023-04-25 -m "Release version v0.3.3+2023-04-25"
git push origin --tags
```


## Publish to OSSRH Staging repository

Use the GitHub Action, run it manually.


## Close and Release

Log in to Sonatype:

[https://s01.oss.sonatype.org/#stagingRepositories](https://s01.oss.sonatype.org/#stagingRepositories)

* Refresh to find the uploaded artifacts (in Staging Repositories).
* Click "Close", and testing of the artifacts ensues. Once this is finished..
* Do a cursory check of the resulting repo (Content tab), expand down into the structure, does it make sense? 
* *VERIFY version!*
* Click "Release" to get it out in the world! **This is irrevocable.**


## Back to SNAPSHOT

Increase the *revision* counter, remove the date, add "-SNAPSHOT".

Commit.