# Installing and deploying Kilnmark

Start with [INSTALL.md](../../../INSTALL.md): what to install first, and the guided install. These
pages say the rest.

| Page | What it covers |
| --- | --- |
| [Choosing a deployment](choosing-a-deployment.md) | The two modes and the three ways to deploy, side by side |
| [A Starter Pack install](starter-pack.md) | Trials, demonstrations and training: example data and example people |
| [Production with the bundled Keycloak](production-bundled-keycloak.md) | Real work, with the Keycloak that comes with Kilnmark; adding people |
| [Production with your own identity provider](production-own-identity-provider.md) | Real work, signing in with your organisation's identity provider |
| [The control script](control-script.md) | Every command, `configure`, `check`, the folders and who may change them |
| [HTTPS and TLS](https.md) | HTTPS for Kilnmark, and TLS to its own database |
| [Running Kilnmark](running-kilnmark.md) | Workers and their sizing, the files folder, external schedulers |
| [Checking a release](checking-a-release.md) | Signatures, checksums and SBOMs |
| [Upgrading](upgrade.md) | Moving to a later release |
| [Uninstalling](uninstall.md) | Taking Kilnmark off a machine |

Backups are in [Backing up and restoring Kilnmark](../backup-and-restore.md). The decisions behind
the install are [ADR 0044](../../adr/0044-installing-with-one-control-script.md) and
[ADR 0051](../../adr/0051-starter-pack.md), decision 17 for the folders and the guided install.
