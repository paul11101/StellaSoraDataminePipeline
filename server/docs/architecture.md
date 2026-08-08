# Local server architecture

The target is a Grasscutter-style authoritative local server, adapted to Stella Sora's actual
wire protocol rather than Genshin's transport.

```text
Stella Sora client
  |
  | HTTPS bootstrap redirected by Fiddler
  v
Local HTTP bootstrap server
  |
  | server list points /game/ to 127.0.0.1:18080
  v
Stella protocol gateway
  |-- IKE key agreement
  |-- AES-GCM / ChaCha20-Poly1305
  |-- Stella packet header and NextPackage chain
  |-- generated Java Protobuf messages
  v
PacketRouter
  |
  +-- PlayerSystem
  +-- StorySystem
  +-- CharacterSystem       (next)
  +-- InventorySystem       (next)
  +-- FormationSystem       (next)
  +-- QuestSystem           (next)
  +-- BattleSystem          (next)
  +-- GachaSystem           (next)
  +-- Shop/Activity systems (next)
  v
PlayerRepository
  |
  +-- JSON save for the current single-player implementation
  +-- replaceable by SQLite or another repository without changing gameplay handlers
```

## Ownership boundary

The client keeps rendering, audio, input, animation, and local presentation scripts. The local
server owns every network-visible rule: account state, characters, inventory, formations,
quests, story progress, stage application and settlement, rewards, energy, shops, gacha, and GM
commands. Fiddler is only the bootstrap redirect and HTTPS inspection layer; it is not the game
server.

## Protobuf strategy

All recovered sources under `resources/tw/v137/protocol/proto/` are compiled by protoc into Java
classes. Implemented systems use those generated types directly. `network.desc` and
`DynamicMessage` remain as a compatibility layer for protocol discovery and future client
versions; they are not the final gameplay model.

## Version upgrades

1. Synchronize a new resource/protocol snapshot with `tools/Sync-RecoveredResources.ps1`.
2. Regenerate Java messages and produce a request/handler coverage diff.
3. Re-run contract tests for unchanged messages.
4. Reverse only changed request/response semantics and update the affected gameplay system.
5. Build a new portable player release.
