# Stella Sora local server

The server is self-contained under this directory. Recovered `.proto` files are compiled into
Java classes during the build. The descriptor set remains available for message discovery,
unknown-packet diagnostics, and cross-version compatibility.

Authoritative gameplay handlers are grouped under `game/system/`. The packet router owns only
transport concerns: message lookup, protobuf decoding, chained `NextPackage` responses, and
persistence boundaries. See `docs/architecture.md` for the intended Grasscutter-style layout.
At startup it writes `runtime/protocol-coverage.json`, classifying every client request as an
implemented Java handler or an explicit temporary fallback. The same JSON is available at
`GET /admin/protocol-coverage` with the configured `X-Admin-Key` header.

Versioned runtime data lives under `resources/<region>/<resource-version>/`:

- `protocol/proto/`: readable recovered protobuf sources.
- `protocol/network.desc`: the exact `FileDescriptorSet` used at runtime.
- `protocol/message_ids.json`: message ID, direction, and protobuf bindings.
- `bootstrap/`: encrypted resource manifest and the editable server-list template.
- `game/`: the complete categorized data-mining snapshot for this client version, including
  raw decoded tables, readable tables, localization, catalogs, validation, and evidence.
  The server currently loads indexed runtime data from `game/readable/`.

After recovering a new client version, run `tools/Sync-RecoveredResources.ps1` with the new
analysis paths and version name. The script copies the complete data-mining output together
with protocol/bootstrap artifacts. Review the generated diff, then point `config.example.json`
at that version's `game/readable/` directory.

Generate Java protobuf classes, build, and test:

```powershell
.\gradlew.bat test installDist --no-daemon
```

Generated Java sources are written to `build/generated/sources/proto/main/java/proto/` and are
not committed. Once dependencies have been cached, add `--offline` for repeatable local builds.

Start the loopback-only server:

```powershell
.\build\install\stella-sora-server\bin\stella-sora-server.bat --config .\config.example.json
```

Build the self-contained Windows player release:

```powershell
.\gradlew.bat portableServerZip --no-daemon
```

The result is `build/release/StellaSoraServer-<version>-windows-x64.zip`. It contains
`StellaSoraServer.exe`, a private Java runtime, all versioned resources/protocol files, the
configuration, and Fiddler setup scripts. Players do not need to install Java.
