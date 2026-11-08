# Choosing a deployment

Kilnmark is installed in one of two modes, chosen at install and never changed
([ADR 0051](../../adr/0051-starter-pack.md)): **Production**, for real work, or a **Starter Pack**
install, for training, demonstrations and development. Install records the mode in Kilnmark's
database at its first start; editing `config/.env` never changes it, and Kilnmark does not start
while the two differ. For the other mode, install Kilnmark afresh.

## The three ways to deploy

| | [Starter Pack](starter-pack.md) | [Production, bundled Keycloak](production-bundled-keycloak.md) | [Production, your own identity provider](production-own-identity-provider.md) |
| --- | --- | --- | --- |
| For | Trials, demonstrations, training, development | Real work, for an organisation with no identity provider of its own, or only Active Directory or LDAP | Real work, for banks and other regulated organisations |
| Answers | `mode=starter-pack` | `mode=production`, `identity=bundled` | `mode=production`, `identity=own` |
| Sign-in | Kilnmark's own Keycloak, always | Kilnmark's own Keycloak, with a password and a one-time code | Your provider, with its second factor |
| Example data | A samples database of its own; an admin turns it on and off under **Settings** (install turns it on) | Never, by any route | Never, by any route |
| Example people | Five, one per role, made by `starter people` | Never; refused at sign-in | Never; refused at sign-in |
| First admin | The admin token, and a person of your own that install offers to add | The same | The admin token; people are your provider's, in its groups |
| Adding people | `people add`; `people import`, a class sharing one temporary password; Keycloak's console | `people add`; `people import`, each person then given a password of their own with `people activate`; Keycloak's console, or Active Directory or LDAP in it | In your provider |
| Templates | Yes | Yes | Yes |
| Backup | Kilnmark's database and Keycloak's | Kilnmark's database and Keycloak's | Kilnmark's database |
| HTTPS | Needed from other machines (`tls=on` or `proxy`) | The same | The same |
| Memory | 5 GB | 5 GB | 4 GB |
| Changing mode | It never becomes Production: install afresh | It never becomes a Starter Pack install: install afresh | The same |

A production install never has the Starter Pack, its samples database or its example people, and
refuses their sign-ins. A trial beside a production install is a second install, in Starter Pack
mode, with its own project, folders and ports.

![The two deployment modes: install sets KILNMARK_MODE once and Kilnmark records it at its first start; a production install never has the Starter Pack, its samples database or its example people, and refuses their sign-ins; a Starter Pack install has the bundled Keycloak, the pack and its marking; a start whose setting differs from the record is refused, and a Starter Pack install never becomes a production one](images/deployment-modes.svg)

## Ways to add people

| Way | Mode | Who runs it | Good for |
| --- | --- | --- | --- |
| `people add` | Either, with the bundled Keycloak | The operator | One person, admins included |
| `people import` | Either, with the bundled Keycloak | The operator | A class (Starter Pack: one shared temporary password) or a team (Production: each waits for `people activate`); never admins, nor on Production anyone who sees sensitive values |
| `people activate` | Either, with the bundled Keycloak | The operator | Giving one waiting person a temporary password of their own |
| Keycloak's console | Either, with the bundled Keycloak | The operator | Changes, password resets, removals |
| Active Directory or LDAP in Keycloak | Production | The operator | People already in the directory |
| Your organisation's identity provider | Production | That service's admins | Banks and other regulated organisations |

Kilnmark itself cannot add people: it holds no credential for Keycloak or for your identity
provider, so no Kilnmark admin can make an account, or learn a password, to get round its
two-person rules ([ADR 0051](../../adr/0051-starter-pack.md), decision 5).
