# A model server or database on this computer

Kilnmark runs in a container, where `localhost` is the container itself, not the computer it runs
on. To reach Ollama, LM Studio or a database running on the same computer, Kilnmark's container
uses the name `host.containers.internal` (or `host.docker.internal`; both mean the same):

| Model server | Base URL under **Settings**, **AI models** |
| --- | --- |
| Ollama | `http://host.containers.internal:11434/v1` |
| LM Studio | `http://host.containers.internal:1234/v1` |

**Check this address** on the **Add a model** form tells you whether it answers.

## Make the model server listen beyond its own machine

Ollama and LM Studio listen only on `127.0.0.1` by default, which a container counts as another
machine, so the check says **Not found** (connection refused).

- **Ollama on Windows:** set `OLLAMA_HOST=0.0.0.0:11434` as a user environment variable (System
  Properties, Environment Variables), then quit Ollama from the tray and start it again.
- **Ollama on Linux**, installed as a service: run `sudo systemctl edit ollama`, add

  ```
  [Service]
  Environment="OLLAMA_HOST=0.0.0.0:11434"
  ```

  then `sudo systemctl daemon-reload && sudo systemctl restart ollama`.
- **LM Studio:** start its server and turn on **Serve on Local Network**.

`OLLAMA_HOST` is Ollama's own setting, read where Ollama runs: it does not go in Kilnmark's
`config/.env`.

Ollama has no password. Listening on `0.0.0.0`, it answers anyone who can reach port 11434: they
can use your GPU, pull models onto your disk and delete yours. Close the port to other computers
in the firewall. On Windows, installing Ollama may add rules that allow `ollama.exe` from every
network; look under **Windows Defender Firewall**, **Inbound Rules**, and turn those off or limit
them. The developer notes ([docs/development.md](../../development.md), "Ollama or LM Studio on
the same PC") give a rule that lets in only the WSL network.

## Windows with WSL's mirrored networking

With `networkingMode=mirrored` in your `.wslconfig` (`%USERPROFILE%\.wslconfig`), the engine's own
route to "this computer" reaches the Podman machine instead of Windows, so a model server on
Windows does not answer at `host.containers.internal` however it listens. The control script
handles this: before every compose command it points `host.containers.internal` and
`host.docker.internal` at this computer's network address (the one Windows uses for its default
route), found again each time because it can change. `check` says which address it uses:

```
& "C:\Program Files\Kilnmark\kilnmark.cmd" check
```

This needs `hostAddressLoopback=true` under `[experimental]` in the same `.wslconfig`, so WSL may reach
Windows by its own address; `check` warns when it is missing. After changing `.wslconfig`, run
`wsl --shutdown`, then start Kilnmark again.

## Choosing the address yourself

Set `KILNMARK_HOST_ADDRESS` in `config/.env` (`C:\ProgramData\Kilnmark\config\.env` on Windows,
`/var/opt/kilnmark/config/.env` on Linux) to the address Kilnmark's container should use for this
computer, for example `KILNMARK_HOST_ADDRESS=192.168.1.100`, then restart Kilnmark
(`kilnmark.sh restart`, or `& "C:\Program Files\Kilnmark\kilnmark.cmd" restart`). It takes the
place of the control script's own choice on either system. Leave it unset unless the usual route
fails.
