# 0042 Error Prone in every compile, and every dependency checked by checksum

Status: Accepted (5 November 2026)

## Context

The SSDF self-assessment ([ssdf-self-assessment.md](../product/ssdf-self-assessment.md)) listed two
gaps in how Kilnmark is built:

- **No static analysis** (PW.7.2, RV.1.2). The compiler's lint warnings were errors, and every
  change had an AI review, but no tool read the code for known kinds of bug.
- **Nothing checked the Gradle libraries** (PW.4.4, PO.3.2). The web UI's lock file pins each
  npm package's checksum, and the build tool's own download is checked, but Gradle took whatever
  Maven Central or the plugin portal served for a version.

Banks' vendor-risk teams ask about both, and a federal buyer may ask for an SSDF attestation that
rests on them (OMB M-26-05).

## Decision

### Static analysis: Error Prone, inside javac

1. **Error Prone** (Google, Apache-2.0) runs as a compiler plug-in in every Java compile, main and
   test, through the `net.ltgt.errorprone` Gradle plug-in (Apache-2.0). Both are build tools: they
   are never shipped and never on a runtime classpath, so `checkLicense` never sees them. The
   versions are in `gradle/libs.versions.toml`, and Dependabot raises them with the rest.
2. **Its findings fail the build.** Its errors always did; the build's `-Werror` makes its warnings
   errors too. There is no separate task to forget and no report to read: if `./gradlew build`
   passes, the code has no finding. CI runs it on every change, in the `build` job.
3. **A finding is fixed, or suppressed where it is, with a reason.** A suppression is
   `@SuppressWarnings("CheckName")` on the narrowest method or class, with a comment saying why the
   code is right as it is.
4. **Checks turned off for the whole build**, each for a reason:
   - Style only, with no bug behind them: `StringConcatToTextBlock`, `RefactorSwitch`,
     `StatementSwitchToExpressionSwitch`, `AssignmentExpression`, `InlineFormatString`.
   - `BadInstanceof`: it flags `x instanceof T t` where `x` is always a `T`, which Kilnmark writes
     on purpose to test for null and name the value in one step.
   - `NonApiType`: Kilnmark puts `LinkedHashMap` in a signature to say the order is kept, which
     `Map` cannot say.
   - `StringSplitter`: it warns that `String.split` drops trailing empty strings, and its fix needs
     Guava, which Kilnmark does not use. All 30 calls were read: one, where a write path ended in
     `/`, was a bug and is fixed (with `split("/", -1)`); the others loop over the parts or take a
     leading one, where a dropped empty part changes nothing.
   - `ArrayRecordComponent`: a record holding an array compares and hashes it by identity. The
     eleven such records (secrets, hashes, image bytes, network addresses) are never compared,
     hashed or used as keys.
5. **Four files are left out until the flow-controls change merges**: `FlowDefinition`, its test,
   `FlowService` and `JobQueue` (`excludedPaths` in `build.gradle.kts`). That change rewrites them,
   so they are checked and fixed after it, not under it; the exclusion then goes. (Done once C4,
   the last flow control, was in: the exclusion is gone, and what Error Prone found in them is
   fixed: locales on case changes, a nested class made static, unused parameters and a field.)

Not chosen:

- **SpotBugs with FindSecBugs**: SpotBugs is LGPL-2.1 and FindSecBugs LGPL-3.0. Neither would ship,
  but ADR 0007 asks for a decision for each LGPL dependency, and their security rules mostly cover
  web frameworks (servlets, Spring, JSP) Kilnmark does not use. SpotBugs also reads compiled
  classes in a separate task, slower and later than javac. It can be added later beside Error
  Prone, with its own licence decision, if a customer's due diligence asks for a security-specific
  analyser.
- **CodeQL**: on a private repository, GitHub's code scanning needs a paid GitHub Code Security
  licence and a change to the repository's settings (the owner's). It would also cover the web
  UI's TypeScript, which nothing analyses beyond the strict compiler today.
- **ESLint for the web UI**: the UI compiles with TypeScript 7, whose compiler has no JavaScript
  API yet, which `typescript-eslint` needs. Revisit when it does.

### Dependency verification: Gradle's, sha256 and signatures

6. **`gradle/verification-metadata.xml`** holds the sha256 checksum of every artifact Gradle
   downloads: libraries, plug-ins, the build's own tools (Error Prone, the licence report, the
   CycloneDX plug-in) and their POM files. A download whose checksum differs, or that the file does
   not list, fails the build before anything uses it. The checksums are generated, not typed: one
   command resolves everything the build can use and writes them
   ([Development](../development.md#dependency-verification)).
7. **Signatures are checked where the artifact is signed.** Maven Central requires a PGP signature
   on everything published there; the metadata trusts each signer's key, and the keys are kept in
   the repository (`gradle/verification-keyring.keys`), so a build never asks a key server. An
   artifact that is not signed, as many on the Gradle plug-in portal are not, is checked by its
   checksum alone.
8. **A dependency update changes the metadata in the same pull request.** Dependabot does not
   regenerate it, so its monthly Gradle pull request fails until someone runs the command on its
   branch and pushes the result; the reviewer then sees each new checksum in the diff.
9. **CI checks the Gradle wrapper's jar** against Gradle's published checksums (the
   `wrapper-validation` action, pinned to a commit) before every job that runs Gradle: build,
   heavy-databases, the supply-chain scan and the release. A changed `gradle-wrapper.jar` fails
   there before it runs. Not covered: a JDK the build downloads for itself where none is installed
   (a Gradle toolchain, not a dependency), and the spikes, built only on request.

## Consequences

- Error Prone's first full run found 5 errors and 327 warnings, of 49 kinds. Fixed among them: two
  conditional expressions in quality prediction that unboxed a null and would have thrown (a text
  column profiled without a count of blanks; a group-by whose row count is unknown); a write path
  ending in `/` that wrote the folder before it as a file; a confirmation mail whose failures could
  escape the server's log; a superclass that made its own subclasses, which can deadlock two threads loading them
  at once; `toLowerCase()` and `getBytes()` without a locale or character set; and identity
  comparisons where values were meant. The pull request that added Error Prone lists what was
  fixed and what was suppressed.
- Twelve of those warnings are in the four files left out (decision 5), waiting for the
  flow-controls change: eight `toLowerCase()` without a locale, three unused parameters and a
  class that can be static.
- Compiling takes longer: Error Prone reads every file it compiles.
- Every dependency change also touches `gradle/verification-metadata.xml`. That is the point: a
  library cannot change under the build without a reviewed diff.
- The SSDF self-assessment marks PW.7.2 and PW.4.4 met. RV.1.2 stays partly met, since Error
  Prone looks for bugs in general and is no security analyser; PO.3.2 too, since base images are
  still pinned by tag.
- Generic JDBC checks a new table's name against the driver's longest column name, not its longest
  table name, which it read and never used (Error Prone found the unused field; it is removed). Using
  the table limit is in the backlog.
