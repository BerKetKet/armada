# Armada

Armada is a free, open-source, root-only Android app providing a comprehensive suite of network measurement, diagnostic, traffic shaping, and tunneling tools. Built with zero runtime dependencies beyond the Kotlin standard library and the Android framework, the app pairs an unprivileged UI with a background root daemon.

## Build, Test, and Verify

Assemble debug APK:
```bash
./gradlew assembleDebug
```

Run desktop unit tests:
```bash
./gradlew :proto:test
```

Enforce dependency rules:
```bash
scripts/check-deps.sh
```

## Updating dependencies

Gradle dependency verification checksums are enforced via `gradle/verification-metadata.xml`. To regenerate verification metadata with sha256 checksums across all modules and configurations:
```bash
./gradlew --write-verification-metadata sha256 assembleDebug :proto:test :app:dependencies :daemon:dependencies :cli:dependencies :proto:dependencies
```
