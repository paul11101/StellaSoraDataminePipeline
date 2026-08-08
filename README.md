# Stella Sora TW Datamine Pipeline and Local Server

This repository contains a reproducible clean-room workflow for extracting structured data and
recovering the network protocol from a locally installed Stella Sora TW client. It also contains
a loopback-only Java server emulator designed around the same architecture principles as
Grasscutter while implementing Stella Sora's actual protocol and data model.

The project is intended for private archival, interoperability research, and testing against a
game installation you are authorized to analyze. It does not authenticate against production
game services, forward gameplay traffic to production, or modify online account data.

## Components

### Datamine pipeline

The Python 3.12 pipeline can:

- inspect official manifests and calculate full-package or incremental patch chains;
- rebuild `data.arcx` and `hotfix.arch` archives;
- decode BAR, AC.DA, XXTEA, LZ4, and protobuf-backed tables;
- recover archive paths and field constants from local IL2CPP metadata;
- emit categorized, localized, readable JSON datasets;
- compare two client versions at table, row, and field level;
- generate reproducible archives with SHA-256 manifests;
- recover message IDs, protobuf schemas, and protocol categories from `lua.arcx`;
- stop with machine-readable diagnostics when a client update changes a format.

### Local Java server

The Java 21 server provides:

- encrypted bootstrap documents and a loopback `/game/` endpoint;
- P-256 ECDH, HKDF-SHA256, AES-GCM, and ChaCha20-Poly1305 support;
- Stella Sora packet framing and chained `NextPackage` responses;
- build-time Java generation from 294 recovered game `.proto` files;
- dynamically loaded descriptors for discovery and future-version compatibility;
- gameplay systems separated from transport routing;
- JSON player persistence and local GM commands;
- a categorized protocol coverage report;
- a self-contained Windows executable with a bundled Java runtime;
- Fiddler Classic scripts that redirect only the two static bootstrap documents.

Current TW v137 coverage contains 973 cataloged messages and 286 client requests. Thirteen
requests have authoritative typed Java handlers; the remaining requests are explicitly reported
as temporary fallbacks and are not presented as completed gameplay logic.

## Repository layout

```text
stella_pipeline/       Python extraction, protocol recovery, adapters, and diffing
tests/                 Python regression tests
server/                Java 21 local server and generated-protobuf build
server/resources/      Versioned protocol, bootstrap, and complete recovered game data
server_emulator/       Fiddler, launcher, and local admin helper scripts
config.example.json    Datamine pipeline configuration template
run.py / run.ps1       Pipeline entry points
```

## Python setup

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -e .
Copy-Item .\config.example.json .\config.json
```

Edit `config.json` and point `game_root` at the local Stella Sora TW installation. Local paths,
caches, extracted runs, and credentials are ignored by Git.

Common commands:

```powershell
.\run.ps1 doctor
.\run.ps1 run
.\run.ps1 run --offline
.\run.ps1 status
.\run.ps1 diff <old-dataset> <new-dataset> --output <diff-directory>
.\run.ps1 protocol --archive <path-to-lua.arcx>
.\run.ps1 protocol --hashes <directory-containing-xxh64.bin> --output <output-directory>
.\run.ps1 protocol --archive <path-to-lua.arcx> --discover-only
```

## Java server development

```powershell
cd .\server
.\gradlew.bat test installDist --no-daemon
.\build\install\stella-sora-server\bin\stella-sora-server.bat --config .\config.example.json
```

After dependencies have been cached, add `--offline` to Gradle commands for repeatable local
builds. Generated protobuf sources are written under
`server/build/generated/sources/proto/main/java/proto/` and are not committed.

Build the self-contained Windows player release:

```powershell
cd .\server
.\gradlew.bat portableServerZip --no-daemon
```

The archive is written to `server/build/release/`. Players must extract the complete directory;
the executable depends on the adjacent private runtime, resources, and configuration files.

## Fiddler Classic setup

Close Fiddler Classic before applying the persistent HTTPS settings:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\server_emulator\Configure-Fiddler.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\server_emulator\Install-FiddlerRule.ps1
```

The rule redirects only:

```text
https://nova-static.stargazer-games.com/meta/serverlist.html
https://nova-static.stargazer-games.com/meta/win.html
```

The returned server list points the game protocol endpoint to
`http://127.0.0.1:18080/game/`. Fiddler is a bootstrap redirect and inspection layer, not the game
server.

## Protocol and gameplay coverage

At startup the Java server writes `server/runtime/protocol-coverage.json`. The same data is
available from `GET /admin/protocol-coverage` with the configured `X-Admin-Key` header. Every
client request is classified by feature category and implementation state.

The implementation roadmap is:

1. player profile, characters, inventory, formations, and unlock state;
2. main story, tutorial, stage application, battle settlement, and rewards;
3. quests, gacha, shops, activities, mail, and local social features;
4. contract tests and client regression for every implemented request;
5. removal of the generic fallback once all required gameplay paths are authoritative.

See [`server/docs/architecture.md`](server/docs/architecture.md) for the server design and
[`server/README.md`](server/README.md) for server-specific build details.

## Version upgrades

The pipeline does not hard-code a single resource version. It probes archive and descriptor
structure before destructive decoding. After recovering a new client version:

1. generate the new datamine and protocol artifacts;
2. run `server/tools/Sync-RecoveredResources.ps1`;
3. regenerate Java protobuf classes;
4. inspect the protocol coverage diff;
5. update only the gameplay systems whose schemas or semantics changed;
6. run the complete Python and Java test suites;
7. build a new portable release.

## Scope and attribution

This project is not affiliated with or endorsed by the game's developer or publisher. Do not
use it to access accounts, systems, or files you are not authorized to analyze. Keep recovered
game data and client-derived artifacts in a private repository unless you have permission to
redistribute them.
