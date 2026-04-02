<p align="center">
  <img src="src/main/resources/icons/claude_13.svg" width="80" height="80" alt="Claude Code Sessions">
</p>

<h1 align="center">Claude Code Sessions</h1>

<p align="center">
  <strong>Monitor and manage all your Claude Code CLI sessions directly from JetBrains IDEs</strong>
</p>

<p align="center">
  <a href="https://github.com/Coding9GmbH/jetbrains-claude-code-sessions/releases/latest"><img src="https://img.shields.io/github/v/release/Coding9GmbH/jetbrains-claude-code-sessions?style=flat-square&color=blue" alt="Latest Release"></a>
  <a href="https://github.com/Coding9GmbH/jetbrains-claude-code-sessions/actions/workflows/build.yml"><img src="https://img.shields.io/github/actions/workflow/status/Coding9GmbH/jetbrains-claude-code-sessions/build.yml?style=flat-square" alt="Build"></a>
  <img src="https://img.shields.io/badge/platform-macOS%20%7C%20Linux%20%7C%20Windows-lightgrey?style=flat-square" alt="Platform">
  <img src="https://img.shields.io/badge/IDE-2024.3%2B-orange?style=flat-square" alt="IDE Version">
</p>

---

Never lose track of your Claude Code sessions again. This plugin adds a **Claude Sessions** tool window to your JetBrains IDE that shows every running, waiting, and finished session across all your projects — with live status updates, CPU monitoring, and one-click actions.

## Features

- **Live session monitoring** — polls every 2 seconds, shows all active Claude sessions across all projects
- **Smart status detection** — Running, Waiting for Input, Waiting for Accept, Finished
- **Environment detection** — see whether a session runs in your JetBrains terminal or an external terminal
- **CPU usage tracking** — per-session CPU percentage at a glance
- **Context usage indicator** — visual progress bar showing how much context window is used
- **One-click actions** — open projects, focus terminals, resume finished sessions
- **Session history** — browse and resume past sessions
- **Search & filter** — find sessions by name, path, or status
- **Sortable columns** — click any column header to sort
- **Desktop notifications** — get notified when a session needs your attention
- **Cross-platform** — macOS, Linux, and Windows

## Keyboard Shortcuts

| Shortcut | Action |
|----------|--------|
| `Enter` | Open or resume the selected session |
| `F5` | Refresh session list |
| `Ctrl+F` / `Cmd+F` | Focus the search field |
| `Delete` | Kill the selected session |
| `Escape` | Clear search |

## Installation

### From GitHub Releases (recommended)

1. Download the latest `.zip` from [Releases](https://github.com/Coding9GmbH/jetbrains-claude-code-sessions/releases/latest)
2. In your JetBrains IDE, go to **Settings → Plugins → ⚙️ → Install Plugin from Disk...**
3. Select the downloaded `.zip` file
4. Restart the IDE

### Build from Source

```bash
git clone https://github.com/Coding9GmbH/jetbrains-claude-code-sessions.git
cd jetbrains-claude-code-sessions
./gradlew buildPlugin
```

The plugin ZIP will be at `build/distributions/claude-code-sessions-*.zip`.

## Requirements

- JetBrains IDE **2024.3** or newer (IntelliJ IDEA, WebStorm, PhpStorm, PyCharm, etc.)
- [Claude Code CLI](https://docs.anthropic.com/en/docs/claude-code) installed and available in your PATH

## How It Works

The plugin monitors `~/.claude/sessions/` for session files created by the Claude Code CLI. It reads session metadata, tracks process state, and parses conversation files to determine the current status of each session. No data is sent anywhere — everything stays local.

## Compatibility

Works with all JetBrains IDEs based on the IntelliJ Platform (2024.3+):

IntelliJ IDEA · WebStorm · PhpStorm · PyCharm · GoLand · RubyMine · CLion · Rider · Android Studio · DataGrip · RustRover

## Contributing

Contributions are welcome! Feel free to open issues or submit pull requests.

```bash
# Run a sandboxed IDE instance with the plugin loaded
./gradlew runIde

# Run the IntelliJ plugin verifier
./gradlew verifyPlugin
```

## License

MIT License — see [LICENSE](LICENSE) for details.

---

<p align="center">
  Built with care by <a href="https://coding9.de">coding9</a>
</p>
