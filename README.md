# holypunch

P2P file transfer utility for sending large directories between machines in different countries.

Sender runs `holypunch send`, gets a 4-word code, shares it. Receiver runs `holypunch receive`, enters the code, picks a destination — transfer begins. The tool attempts a direct peer-to-peer UDP connection first (NAT hole punching); if that fails, traffic is relayed through the signaling server. Transfer resumes automatically after interruption — only missing chunks are re-sent.

```
Sender                                    Receiver
  holypunch send ./photos                   holypunch receive
  ────────────────────────────              ────────────────────
  Scanning: 847 files, 76.2 GB             Enter code: wave-moon-fire-blue
  Hashing... [████████░░] 78%              Save to [C:\Users\Anna\]: D:\vacation
                                           Connecting... paired with sender!
  ╔══════════════════════════════╗
  ║  Code:  wave-moon-fire-blue  ║         Establishing direct connection...
  ║  Share with your recipient!  ║          connected (direct)
  ╚══════════════════════════════╝
                                           Receiving:
  Peer joined!                             [████████████░░░░] 54%  41.1 / 76.2 GB
  connected (direct)                         34.7 MB/s  ETA 10m 22s  [direct]

  Sending:
  [████████████░░░░] 54%  41.1 / 76.2 GB
    34.7 MB/s  ETA 10m 22s  [direct]

  Complete! 847 files sent.               Complete! All files received and verified.
```

---

## Contents

- [Requirements](#requirements)
- [Quick start — using a pre-built binary](#quick-start--using-a-pre-built-binary)
- [Sending files](#sending-files)
- [Receiving files](#receiving-files)
- [Resuming an interrupted transfer](#resuming-an-interrupted-transfer)
- [Building from source](#building-from-source)
  - [Regular JAR](#regular-jar)
  - [Native executable (.exe on Windows)](#native-executable-exe-on-windows)
- [Running your own server](#running-your-own-server)
  - [Locally](#locally)
  - [Railway (cloud)](#railway-cloud)
- [How it works](#how-it-works)
- [Security](#security)

---

## Requirements

**To run holypunch (end users):**
- Java 21 or later — [download](https://adoptium.net/) — OR use the native `.exe` which needs nothing
- Network access to the signaling server

**To build from source:**
- Java 21 JDK + Maven (the `mvnw` wrapper is included)

**To build the native `.exe`:**
- [GraalVM JDK 21](https://www.graalvm.org/downloads/) with `native-image` installed
- Windows: Visual Studio 2022 Build Tools — workload "Desktop development with C++"
- Must run from the **x64 Native Tools Command Prompt for VS 2022**

---

## Quick start — using a pre-built binary

Download `holypunch.exe` from Releases and place it somewhere in your PATH.

```
holypunch --help
holypunch send --help
holypunch receive --help
```

By default the client connects to the public signaling server. To use your own server:

```
holypunch send   ./my-folder --server wss://your-server.railway.app/signal
holypunch receive             --server wss://your-server.railway.app/signal
```

Or set the environment variable once and omit the flag:

```powershell
# PowerShell
$env:HOLYPUNCH_SERVER = "wss://your-server.railway.app/signal"
holypunch send   ./my-folder
holypunch receive
```

```bash
# bash / zsh
export HOLYPUNCH_SERVER=wss://your-server.railway.app/signal
holypunch send   ./my-folder
holypunch receive
```

---

## Sending files

```
holypunch send [<directory>] [--server <url>]
```

| Argument | Default | Description |
|----------|---------|-------------|
| `<directory>` | current directory | Directory to send (all files recursively) |
| `--server`, `-s` | `$HOLYPUNCH_SERVER` or `ws://localhost:8080/signal` | Signaling server URL |

**Example:**

```
holypunch send D:\vacation-photos
```

holypunch will:
1. Scan the directory and report the file count and total size
2. Hash files in the background while waiting for the receiver
3. Display a 4-word code — share this with the recipient via any channel (chat, phone)
4. Wait for the receiver to connect
5. Attempt a direct P2P connection (NAT hole punching)
6. Fall back to relay through the signaling server if P2P fails
7. Stream all files with a live progress bar showing speed and ETA

Files matching `.holypunch-*` are automatically excluded (state and temp files).

---

## Receiving files

```
holypunch receive [<code>] [<destination>] [--server <url>]
```

| Argument | Default | Description |
|----------|---------|-------------|
| `<code>` | prompted | 4-word session code from the sender |
| `<destination>` | prompted (defaults to current directory) | Where to save the files |
| `--server`, `-s` | `$HOLYPUNCH_SERVER` or `ws://localhost:8080/signal` | Signaling server URL |

**Interactive (no arguments):**

```
holypunch receive
Enter code: wave-moon-fire-blue
Save to [C:\Users\Anna\]: D:\vacation
```

**Non-interactive (arguments provided):**

```
holypunch receive wave-moon-fire-blue D:\vacation
```

holypunch will:
1. Connect to the signaling server and pair with the sender
2. Attempt a direct P2P connection
3. Fall back to relay if needed
4. Receive all files with a live progress bar
5. Verify SHA-256 checksum for each file after receipt
6. Save a `.holypunch-state.json` in the destination for crash recovery (deleted on success)

---

## Resuming an interrupted transfer

No special action required. If the transfer is interrupted (network loss, crash, power failure), simply run the same `receive` command again pointing to the same destination directory:

```
holypunch receive wave-moon-fire-blue D:\vacation
```

holypunch reads `.holypunch-state.json` and reports what was already received:

```
Connecting... paired with sender!
connected (direct)
Resume state found — 41.1 GB already received.
Resuming — 2,847 chunks remaining.
```

Only the missing 4 MB chunks are re-sent — never whole files.

> **Note:** The sender re-scans its directory on each run. If any files in the source directory changed since the last session, the manifest hash will differ and the receiver will start a fresh transfer.

---

## Building from source

Clone the repo and use the included Maven wrapper:

```
git clone https://github.com/sever0x/holypunch
cd holypunch
```

### Regular JAR

```
.\mvnw -pl holypunch-client -am package -DskipTests
```

Output: `holypunch-client\target\holypunch-client-0.0.1-SNAPSHOT.jar`

Run:

```
java -jar holypunch-client\target\holypunch-client-0.0.1-SNAPSHOT.jar send ./folder
java -jar holypunch-client\target\holypunch-client-0.0.1-SNAPSHOT.jar receive
```

### Native executable (.exe on Windows)

**Prerequisites:** GraalVM JDK 21 + Visual Studio 2022 C++ Build Tools (see [Requirements](#requirements)).

Open **x64 Native Tools Command Prompt for VS 2022**, then:

```
cd E:\path\to\holypunch
mvnw -pl holypunch-client -am native:compile -Pnative
```

Output: `holypunch-client\target\holypunch.exe`

Rename or copy to a directory in your PATH:

```
copy holypunch-client\target\holypunch.exe C:\tools\holypunch.exe
```

The `.exe` is fully self-contained — no Java required to run it.

---

## Running your own server

The signaling server handles pairing and acts as a relay fallback if direct P2P fails. It is a lightweight Spring Boot WebFlux application.

### Locally

```
.\mvnw -pl holypunch-server spring-boot:run
```

Server starts on `http://localhost:8080`. Use `ws://localhost:8080/signal` as the server URL.

Health check: `http://localhost:8080/actuator/health`

### Railway (cloud)

The repo includes a `Dockerfile` and `railway.toml` for one-click deployment.

1. Push this repository to GitHub
2. Go to [railway.com](https://railway.com) → **New Project** → **Deploy from GitHub repo**
3. Select the repo — Railway detects the `Dockerfile` automatically
4. After deployment: **Settings** → **Networking** → **Generate Domain**
5. Your server URL is `wss://<generated-domain>.up.railway.app/signal`

**Recommended plan:** Hobby ($5/month). Since the primary path is direct P2P, the server only handles signaling (~a few KB per session) and rarely carries bulk transfer traffic. Relay mode (when P2P fails) uses egress bandwidth at $0.05/GB.

**Environment variables** (optional, set in Railway dashboard):
- `PORT` — set automatically by Railway; the server reads it via `server.port=${PORT:8080}`

### Other platforms

The server is a standard Spring Boot application packaged as a fat JAR. Build it:

```
.\mvnw -pl holypunch-server -am package -DskipTests
```

Output: `holypunch-server\target\holypunch-server-0.0.1-SNAPSHOT.jar`

Run anywhere Java 21 is available:

```
java -jar holypunch-server\target\holypunch-server-0.0.1-SNAPSHOT.jar
```

Set `PORT` environment variable to control the listening port.

---

## How it works

### Connection setup

1. **Signaling.** Both clients connect to the signaling server via WebSocket. The sender gets a 4-word code; the receiver enters it. The server pairs them and proxies ICE candidate exchange (~a few KB total).

2. **NAT hole punching (ICE).** Each client collects candidates:
   - *host* — local interface address (covers same-LAN transfers)
   - *srflx* — public address discovered via STUN (`stun.l.google.com`)

   Both sides simultaneously send probe packets to each other's candidates. For most home NATs (endpoint-independent mapping) this opens a direct path. ~80-90% success rate.

3. **Relay fallback.** If no direct path is found within 15 seconds (symmetric NAT, strict corporate firewall, CGNAT), the receiver requests relay mode. The signaling server forwards all traffic between the two clients. This is the same server, just used as a dumb pipe.

### Transfer protocol

After the connection is established (direct or relay):

1. **Key exchange.** Ephemeral X25519 key agreement with the session code as a binding factor. All subsequent traffic is encrypted with AES-256-GCM.

2. **Resume state.** The receiver sends its current state (which chunks it already has). The sender skips already-received chunks.

3. **Streaming.** The sender streams 4 MB chunks as binary frames `[fileIndex][chunkIndex][data]`. The receiver writes each chunk directly to its final position on disk using a `FileChannel` at the correct byte offset.

4. **Verification.** After all chunks of a file are received, the receiver computes the SHA-256 and compares it to the manifest. On mismatch, it requests specific chunks to be re-sent.

5. **Durability.** Before marking a chunk as received, the receiver calls `FileChannel.force()` and then atomically updates `.holypunch-state.json` via `Files.move(ATOMIC_MOVE)`. A crash between these two operations results in at most one chunk being re-sent — never in silent data corruption.

### Chunk size and performance

- Chunk size: **4 MB**
- Reliable UDP transport (direct path): sliding-window ARQ, window = 2048 packets, packet MTU = 1280 bytes
- Relay transport: WebSocket over TCP

---

## Security

- **End-to-end encrypted.** Traffic is encrypted with AES-256-GCM before leaving the client, regardless of whether the path is direct or relay. The signaling server never sees plaintext content.

- **Key exchange.** Uses a SPAKE2-like construction over X25519: each side masks its ephemeral public key with `SHA-256("holypunch-v1:" || sessionCode)` before transmitting. A passive attacker who captures the exchange cannot derive the shared secret without the session code AND the ephemeral private key (which is never transmitted). A passive attacker who knows the code still cannot compute the shared secret without the private key.

- **Session codes.** 4-word codes from a ~600-word dictionary — roughly 10^10 combinations. Codes expire after 30 minutes. The server rate-limits join attempts to 20 per minute per IP.

- **No persistent keys.** Keypairs are ephemeral — generated fresh for each session and discarded afterwards.

- **Trust model.** Anyone who obtains the session code before the legitimate receiver can pair with the sender. Share codes over a channel you trust (Signal, phone call). Do not post codes publicly.
