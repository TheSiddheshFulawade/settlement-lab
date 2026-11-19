# 01 · Build skeleton (L-01)

## What it is, in plain words

One command, `./mvnw verify`, is the only gate. If it is green, the code compiles, every test passes,
coverage is at least 85 %, the format is right and the package rules hold. Nothing else counts.

- **Maven wrapper** (`mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`): a small script that
  downloads the exact Maven version named in the properties file, checks its SHA-256, and runs it.
  Nobody needs Maven installed, and everybody runs the same Maven. The `only-script` type keeps the
  binary `maven-wrapper.jar` out of git.
- **`maven.compiler.release` 21**: compiles against the Java 21 API, not just the 21 bytecode format.
  `--release` stops you calling a method that only exists in 22+, which `-source/-target` would not.
  So the code builds on JDK 21 and 25, and we run on 25.
- **Enforcer**: fails fast on the wrong JDK or Maven, on two versions of the same library in the tree
  (`dependencyConvergence`), and on any plugin without a pinned version (`requirePluginVersions`).
- **JaCoCo**: an agent records which lines the tests ran; `check` fails under 85 % line coverage.
  The `runner` package is excluded: it is a command-line shell, measured by running it.
- **Spotless + palantir-java-format**: formatting is a machine decision, not a review comment.
  `./mvnw spotless:apply` fixes it; `verify` only checks.
- **ArchUnit** (`ArchitectureTest`): reads the compiled classes and fails if, say, `domain` imports
  anything outside `java.*`. The architecture is a test, so it cannot rot quietly. Rules for packages
  that do not exist yet allow an empty match; each loses that flag when its package gets a class.
- **CI** (`.github/workflows/verify.yml`): the same `./mvnw verify` on JDK 21 and 25.

## Interview questions

1. **Why `--release 21` instead of `-source 21 -target 21`?**
   `-source/-target` only control language level and bytecode version; you still compile against the
   JDK you run, so you can call a JDK 25 API by accident and fail at runtime on 21. `--release` also
   swaps in the Java 21 API signatures, so that mistake is a compile error.
2. **What does the Maven wrapper's `distributionSha256Sum` protect against?**
   A tampered or corrupted Maven download (a compromised mirror or proxy). The script hashes the zip
   and refuses to run it if the hash differs. It is a small supply-chain control.
3. **Why make architecture rules a test instead of a wiki page?**
   A wiki page is not checked; a test fails the build the moment someone imports Spring into the
   domain. It also documents the rules in a place a new developer will meet them.
4. **What does `failOnEmptyShould` catch, and why did we switch it off for some rules?**
   A rule whose `that()` matches no classes passes trivially; usually that means a typo or a renamed
   package. We allow it only for packages not built yet, and remove the flag once they have classes.
5. **Is 85 % line coverage a quality guarantee?**
   No. It shows which lines ran, not that they were checked. That is why the invariants are proven
   with properties and stress tests. Coverage is a floor that catches untested code, not a goal.
