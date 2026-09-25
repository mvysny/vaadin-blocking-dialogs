# Contributing

Thank you so much for making the library better.
Please feel free to open bug reports to discuss new features; PRs are welcome as well :)

## Tests

Uses JUnit, with [Karibu-Testing](https://github.com/mvysny/karibu-testing)
driving Vaadin UI tests in-JVM (no browser needed). Run `./gradlew build` to
build and test everything, or `./gradlew test` to only run tests.

# Releasing

To release the library to Maven Central:

0. Be on `master` with a clean working tree, and check that CI is green for that commit: the local
   build below runs one JDK, CI runs 21 and 25 across three vendors.
1. Edit `build.gradle.kts` and remove `-SNAPSHOT` in the `version=` stanza, e.g. "0.1"
2. Edit `README.md`: bump the version in the Installation snippets to match the release.
   On the first release, also drop the "Nothing is published to Maven Central yet" banner.
3. On JDK 24+, run `./gradlew clean build publish closeAndReleaseStagingRepositories`.
   On JDK 21-23 the loom runner's tests refuse to start unless you pass
   `-Dblockingdialogs.uifiber.loom.allowPinningJdk=true`. The signing keys and the Central Portal
   user token (`sonatypeUsername`/`sonatypePassword`) come from `~/.gradle/gradle.properties`.
4. (Optional) watch [Maven Central Publishing Deployments](https://central.sonatype.com/publishing/deployments) as the deployment is published.
5. Commit with the commit message of simply being the version being released, e.g. "0.1"
6. git tag the commit with the same tag name as the commit message above, e.g. `0.1`
7. `git push`, `git push --tags`
8. Add the `-SNAPSHOT` back to the `version=` while increasing the version to something which will be released in the future,
   e.g. 0.2-SNAPSHOT, then commit with the commit message "0.2-SNAPSHOT" and push.
