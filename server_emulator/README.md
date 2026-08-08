# Stella Sora TW Local Server Helpers

These scripts configure and launch the loopback-only Java server. The implementation is a
clean-room reimplementation based on local client behavior. Fiddler redirects only the static
bootstrap documents; official account authentication and production gameplay requests are not
proxied into the local server.

## Start the development server

```powershell
cd C:\path\to\automation
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\server_emulator\Start-LocalServer.ps1
```

Check its status:

```powershell
Invoke-RestMethod http://127.0.0.1:18080/health
```

Validate configuration, descriptors, and resources without opening the listener:

```powershell
cd .\server
.\gradlew.bat run "--args=--check" --no-daemon
```

Runtime saves and diagnostic logs are stored under `server/runtime/` and are ignored by Git.

## Fiddler Classic

Close Fiddler Classic, then run:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\server_emulator\Configure-Fiddler.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\server_emulator\Install-FiddlerRule.ps1
```

Restart Fiddler Classic after installing the rule. It matches only:

```text
https://nova-static.stargazer-games.com/meta/serverlist.html
https://nova-static.stargazer-games.com/meta/win.html
```

The local server list directs the game protocol to `http://127.0.0.1:18080/game/`.

## Local GM commands

Commands can be entered directly in the server console:

```text
help
list character
list item <query>
list story
give character 107 80
give all-characters 80
give item 2 9999
story complete 100
set world-class 10
state
```

They can also be sent from another PowerShell window:

```powershell
.\server_emulator\Invoke-LocalCommand.ps1 "give character 107 80"
.\server_emulator\Invoke-LocalCommand.ps1 "give item 2 9999"
```

State-changing commands are persisted immediately. The next ping can deliver the updated
`PlayerInfo` through `NextPackage`.

## Portable player release

Build the player-facing archive with:

```powershell
cd .\server
.\gradlew.bat portableServerZip --no-daemon
```

The archive contains `StellaSoraServer.exe`, a bundled Java runtime, versioned resources,
configuration, and these Fiddler scripts. The executable must remain beside its `app/`,
`runtime/`, and resource directories.
