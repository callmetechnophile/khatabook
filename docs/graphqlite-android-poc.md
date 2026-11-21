# KHATAWAS GraphQLite Android NDK POC

Status: design and build spike only. This document does not claim Android compatibility or a successful build.

## Objective

Validate whether the upstream `colliery-io/graphqlite` SQLite extension can be cross-compiled for Android and invoked from the KHATAWAS Kotlin application through JNI. The intended end state is one encrypted local database file containing the existing relational ledger plus GraphQLite graph records.

Upstream project: https://github.com/colliery-io/graphqlite
License: MIT (verify the exact pinned revision before vendoring).

## Findings from upstream source inspection

- GraphQLite is a C SQLite extension. Its extension entry point is `sqlite3_graphqlite_init`.
- The upstream build uses a Makefile, Bison and Flex. The scanner and grammar are generated during the build.
- The upstream Makefile currently targets desktop environments and links the host SQLite library. It does not document Android NDK, Android ABI, JNI, or SQLCipher support.
- The extension uses SQLite extension APIs and vendored SQLite headers.
- Therefore, Android support requires an Android-specific CMake build. Host Bison/Flex should generate parser C sources; the Android NDK compiler should compile those generated C sources and the extension sources for each target ABI.
- Android's NDK supports C/C++ shared and static libraries through CMake and Gradle ExternalNativeBuild: https://developer.android.com/ndk/guides/cmake

## Non-negotiable connection constraint

GraphQLite must execute against the same SQLite database file and encryption configuration as the local ledger. A second plaintext graph database is not an acceptable substitute.

The current KHATAWAS code uses the Java SQLCipher API (`net.sqlcipher.database.SQLiteDatabase`). Its Java database handle is not automatically a native `sqlite3*` handle that can be passed to JNI.

Two integration designs must be evaluated:

1. **Preferred if supported by the pinned SQLCipher build:** compile GraphQLite into, or load it through, the same SQLCipher SQLite engine and open the encrypted database through a single native connection manager. Expose a narrow JNI API to Kotlin. This may require migrating ledger database access from Java SQLCipher APIs to the native connection manager.
2. **Fallback:** maintain separate Java-SQLCipher and native-SQLCipher connections to the same encrypted file, using the same SQLCipher version, key derivation, cipher settings, WAL settings, and busy handling. Prove concurrent read/write correctness and crash recovery before use.

Do not connect GraphQLite to a plain SQLite connection and assume it can read the SQLCipher database. Do not silently create a separate unencrypted graph file.

## POC stages

### Stage 0 — Pin and inventory upstream

- Pin an exact GraphQLite commit SHA.
- Record the upstream license and source manifest.
- Identify all generated files and build-time tools.
- Confirm whether the source can be compiled as a static object library or shared extension without host-only dependencies.
- Do not use a floating branch or download source at build time.

### Stage 1 — Android NDK compile spike (plain SQLite only)

- Add an isolated native target; do not change the production ledger database.
- Generate Flex/Bison parser sources on the host.
- Compile GraphQLite C sources with Android NDK for `arm64-v8a`.
- Build a JNI test library.
- Open a disposable test database with a native SQLite connection.
- Register GraphQLite using its extension initialization entry point.
- Run a relational SQL insert, a Cypher node/edge create, and a Cypher traversal query.
- Add `armeabi-v7a` only after ARM64 passes.
- This stage proves NDK compilation and JNI execution only. It does not prove SQLCipher compatibility.

### Stage 2 — SQLCipher compatibility gate

- Pin the exact SQLCipher Android/native version used by the app.
- Determine how the SQLCipher library exposes its SQLite extension API and whether the extension can be statically registered on the same connection.
- Verify SQLCipher compile-time options and SQLite API version against GraphQLite's expected API.
- Use the same encrypted database connection for relational and graph queries.
- Test database creation, close/reopen, wrong-key rejection, WAL, concurrent access, rollback, process death, and recovery.
- If the SQLCipher engine cannot safely host GraphQLite, stop. Do not ship two inconsistent local stores.

### Stage 3 — Kotlin API

Expose only a small API:
- `openDatabase(path, key)`
- `executeSql(sql, typedArgs)`
- `executeCypher(query, typedArgs)`
- `closeDatabase()`

Requirements:
- Run database work off the main thread.
- Use prepared/bound parameters. Never interpolate customer names or speech text into query strings.
- Return structured errors and typed rows.
- Serialize access to each native connection.
- Never log SQL parameters containing customer or financial data.
- Zero sensitive key buffers where practical; do not retain raw key material in Kotlin strings.

### Stage 4 — KHATAWAS graph model

Only after Stages 1–3 pass:
- Keep customers, ledger entries, payments, and audit history as permanent relational records.
- Add graph projections for relationships such as customer-to-ledger-event and event-to-correction.
- Use opaque record IDs in graph properties. Do not place names, phone numbers, or raw transcripts in graph nodes.
- Do not calculate authoritative balances from graph traversals.
- Keep cloud sync to SurrealDB independent of graph-query availability.

## Acceptance tests

A POC passes only when all are true:

1. Android ARM64 build succeeds using the pinned upstream source.
2. JNI opens a database and registers GraphQLite without a crash.
3. A relational SQL row and graph node/edge are stored in the same database file.
4. A Cypher traversal returns the expected result.
5. A transaction containing both relational and graph writes rolls back atomically on forced failure.
6. Close/reopen preserves both data models.
7. SQLCipher encryption is verified independently: file inspection does not reveal inserted test strings, correct key opens the database, wrong key fails.
8. Tests pass on a physical Android device, not only an emulator.
9. No regression to existing ledger insert/read tests.
10. APK size, native memory, query latency, and cold-start impact are measured.

## Explicit non-goals

- Replacing the cloud SurrealDB database.
- Replacing the current financial ledger with graph records.
- Introducing a second local plaintext database.
- Enabling graph-based balance computation.
- Shipping GraphQLite before SQLCipher compatibility and physical-device tests pass.

## Current repository integration facts

- Android NDK version: `27.1.12297006`.
- CMake version: `3.22.1`.
- Existing native build: `app/src/main/cpp/CMakeLists.txt`.
- Existing native library: `khatawas_jni`, currently linked to RNNoise.
- Existing SQLCipher dependency: `net.zetetic:android-database-sqlcipher:4.5.4`.
- Existing Android ABIs: `armeabi-v7a`, `arm64-v8a`.

The current CMake target must be extended without breaking RNNoise. The POC must not change the production database implementation until the SQLCipher gate passes.
