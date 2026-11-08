# Production with the bundled Keycloak

Kilnmark comes with **Keycloak**, an open-source identity provider, for an organisation that has
none of its own, or only Active Directory or LDAP (which Keycloak can read). It is the compose
profile `auth` ([ADR 0014](../../adr/0014-authentication.md)), and needs about 1 GB of memory more.
For banks and other regulated organisations, signing in with your own identity provider is
recommended instead ([Production with your own identity provider](production-own-identity-provider.md)).

## Install it

Answer `production` and then `bundled`, or in an answers file:

```
mode=production
identity=bundled
email_domain=bank.example
host=kilnmark.bank.example
tls=proxy
port=8780
https_port=8443
db_port=5442
keycloak_port=8180
admin_email=ada.lovelace@bank.example
admin_first_name=Ada
admin_last_name=Lovelace
```

Install then:

- turns the `auth` profile on in `config/.env`, with new secrets for Keycloak's database, Keycloak's
  own admin and the client Kilnmark signs in with;
- sets sign-in with it: `KILNMARK_OIDC_ISSUER=http://keycloak:8080/realms/kilnmark`, the client
  `kilnmark`, and the five groups `kilnmark-viewers`, `kilnmark-stewards`, `kilnmark-designers`,
  `kilnmark-admins` and `kilnmark-sensitive-data`;
- sets where browsers reach Kilnmark and Keycloak (`KILNMARK_URL`, `KILNMARK_KEYCLOAK_URL`) from
  the host, the ports and TLS;
- starts Kilnmark and Keycloak; `keycloak-setup` waits for Keycloak, then makes the realm
  `kilnmark`: the groups, the client, a sign-in of a password and a one-time code, and Kilnmark's
  sign-in page;
- adds **Kilnmark's first admin**, the person you named, in `kilnmark-admins`, with the temporary
  password you typed (or piped in). They choose their own at their first sign-in, and set up a
  one-time code.

Skip the first admin (press Enter at the email) and add them later with
`configure admin_email=... admin_first_name=... admin_last_name=...`, or with `people add`.

**Two admins, in two places.** Keycloak's own admin, `KILNMARK_KEYCLOAK_ADMIN` (`admin` unless you
set it), signs in to **Keycloak's console**, in Keycloak's realm **master**, to manage people: it
is not a Kilnmark account and cannot sign in to Kilnmark. **Kilnmark's first admin** is a person in
the realm **kilnmark**, in `kilnmark-admins`, who signs in to **Kilnmark**.

Keycloak makes its admin on its first start only: changing `KILNMARK_KEYCLOAK_ADMIN_PASSWORD` later
changes nothing, and `keycloak-setup` then cannot sign in. Change the password in Keycloak's console
first, then in `config/.env`.

Each person sets up a one-time code (any authenticator app) on their first sign-in. Keycloak does
not count setting it up as using it, so Kilnmark then asks them to sign in once more, with the code.
Keycloak's tokens last 5 minutes (so that disabling someone, or taking them out of a group, shows
within 5 minutes); Kilnmark renews them while the person works. Keycloak's session for the realm
ends after 2 hours unused or 8 hours at most. Signing out of Kilnmark ends the Keycloak session too.

## From other machines

On one machine, `http://localhost` works as it is: browsers treat localhost as secure, so
Kilnmark's session cookie (sent over HTTPS only) still works there. Keycloak is published on this
machine only. For other machines, put a proxy on this machine that ends TLS in front of both,
answer `tls=proxy` and the host name the proxy serves (`configure host=... tls=proxy` changes an
install), and have the proxy pass `https://<host>` to Kilnmark's port and
`https://<host>:<keycloak_port>` to Keycloak's. `configure` restarts Kilnmark, which runs
`keycloak-setup` again so the realm follows the new addresses ([HTTPS and TLS](https.md)).

## Adding people to the bundled Keycloak

With the bundled Keycloak, in either mode, the control script adds people of your own. It runs
`keycloak-setup` once, with Keycloak's own admin from `config/.env`: Kilnmark itself holds no
Keycloak credential, so it cannot add people ([ADR 0051](../../adr/0051-starter-pack.md),
decision 5). Run these as the operator (on Linux, `sudo -iu kilnmark /opt/kilnmark/kilnmark.sh ...`):

| | Linux | Windows |
| --- | --- | --- |
| Add one person | `kilnmark.sh people add` | `kilnmark.cmd people add` |
| Add the people in a file | `kilnmark.sh people import people.csv` | `kilnmark.cmd people import people.csv` |
| Activate one waiting person | `kilnmark.sh people activate ada@bank.example` | `kilnmark.cmd people activate ada@bank.example` |
| List who is there | `kilnmark.sh people list` | `kilnmark.cmd people list` |
| Expire unused temporary passwords now | `kilnmark.sh people expire` | `kilnmark.cmd people expire` |

- **`people add`** asks for the person's email, which is their user name, their first and last
  name, their groups (separated by `;`), and a temporary password, typed twice and not shown. To
  pipe them in instead, give five lines: email, first name, last name, groups, password. This is
  the only way the script puts someone in `kilnmark-admins`, one person at a time, as Kilnmark's
  two-person rules need.
- **`people import FILE`** adds everyone in a CSV file whose first line is
  `email,first_name,last_name,groups`. `groups` is a `;`-separated list of Kilnmark's groups,
  never `kilnmark-admins`; lines starting with `#`, and blank lines, are skipped; a name may not
  hold a comma, nor any of the characters Keycloak refuses in names (`( ) / \ ; & < > " $ % ! # ?
  * ~ | ^ = [ ] { }`). It checks the whole file before changing anything: a well-formed address, a
  known group and no address twice. If any line is wrong it names each one by its number, never
  its values, and makes nobody. At the end it says how many were made, already there and refused;
  should Keycloak refuse someone part way, it says how many it made before stopping.
  - On a **Starter Pack install**, for a class, it asks once for a temporary password the whole
    file shares (or takes one line on standard input), and everyone may sign in with it at once.
  - On a **production install** nobody is given a password: each person is made **disabled**,
    waiting to be activated, and the operator gives each a temporary password of their own with
    `people activate`, one by one. A file may not give `kilnmark-sensitive-data` either: like
    admins, people who see sensitive values are added one at a time with `people add`.

  The release has an example, `containers/keycloak/people.example.csv`, at example.com addresses,
  which are reserved and receive no mail (on production, its last line is refused: Priya sees
  sensitive values).
- **`people activate EMAIL`** gives one person waiting to be activated (imported on production, or
  disabled because their temporary password went unused) a temporary password of their own, typed
  twice and not shown, or piped in, and enables them. It takes only someone waiting for a first
  password: no password at all, or only the temporary one these commands gave them, unused. It
  refuses someone already active, anyone with a password of their own or a one-time code (they
  have signed in, and were disabled on purpose: enable them in Keycloak's console), and anyone from
  a directory or another identity provider.
- **`people list`** shows each person in the realm with their Kilnmark groups, whether they are
  `active`, `waiting-to-be-activated` or `disabled`, and `must-choose-password=yes` while they have
  not yet signed in with their temporary password. It shows no password.

Someone already there with that email is left exactly as they are, their password and groups too,
and reported as already there; running a command again changes nothing. Addresses at kilnmark.com,
the Starter Pack's example people's, are refused, so a real person is never taken for an example
one. People added this way are in exactly the Kilnmark groups they were given, and never in the
pack's group: should someone made land in another group of Kilnmark's (the realm has it as a
default group, say), or in a group `config/.env` maps to a role, they are deleted again and the
command stops. A group `config/.env` maps to the admin role, or to seeing sensitive values, counts
as `kilnmark-admins` or `kilnmark-sensitive-data` in an import file, and is refused there alike.
People cannot change their own email, names or user name in the bundled Keycloak: an admin does,
in its console.

Someone given a temporary password stays disabled until Kilnmark's audit log has recorded them,
and is enabled only then: if Kilnmark cannot record them, they stay disabled until you run
`people activate EMAIL` once it answers. The commands check that Kilnmark answers first, so nobody
is made without that record, and act only where the mode Kilnmark recorded in its database is the
one `config/.env` says. Kilnmark's audit log records each person made (`auth.person_added`, with
their groups), each import (`auth.people_imported`) and each activation
(`auth.person_activated`), never a password; Keycloak records every change to people as an admin
event too. Keycloak's realm is stamped once with this install's id and mode: `keycloak-setup` then
refuses a Keycloak of another install, of the other mode, or, on production, one that still has
the Starter Pack's people or group. Its lookups fail closed: one Keycloak does not answer counts
against the change, and a person's groups are checked by their full path.

To add people in Keycloak's console instead, open `KILNMARK_KEYCLOAK_URL/admin`, sign in as
`KILNMARK_KEYCLOAK_ADMIN` with `KILNMARK_KEYCLOAK_ADMIN_PASSWORD`, realm **kilnmark**, **Users**;
or connect Active Directory or LDAP under **User federation**.

## Unused temporary passwords expire

Each temporary password works once: at their first sign-in Keycloak has the person choose their
own, and set up a one-time code. Anyone still holding one more than
`KILNMARK_PEOPLE_ACTIVATION_HOURS` (72 unless you set it, 1 to 720) after it was set is disabled,
at most an hour late, and Kilnmark's audit log says so (`auth.person_disabled`). That includes
Kilnmark's first admin; `people activate` gives them a new one, and `KILNMARK_ADMIN_TOKEN` stays the
way in meanwhile. Only the temporary passwords these commands set expire (each is marked
`kilnmark-temporary` in Keycloak, and the mark goes when its person chooses their own). A temporary
password with a one-time code set up on it, but no new password, still expires, and the code is
taken away again.

The check runs at every start, at every `people` command, and every hour as `people expire`, which
`up` sets up and `uninstall` removes:

- on **Linux**, a systemd timer of the account, `kilnmark-<project>-people-expire.timer`, which
  runs `"/opt/kilnmark/kilnmark.sh" people expire` from the program folder. Rootless Podman keeps
  running while nobody is signed in, as install turns lingering on; without systemd, `up` says so:
  run `people expire` every hour from the account's crontab instead;
- on **Windows**, a scheduled task of the operator's, `Kilnmark <project> people expire`, which runs
  `"C:\Program Files\Kilnmark\kilnmark.ps1" people expire` with Windows PowerShell named by its full
  path, while the operator is signed in to Windows, as the Podman machine or Docker Desktop does.

The task or timer runs the control script as the operator every hour, so `up` sets it up only where
nobody else may change what a run uses, and `people expire` checks it again first (a second line
of defence: [The control script](control-script.md#who-may-change-what-a-run-uses)). `status` and
`check` say whether it is set up. The expiry holds only while the timer and Kilnmark's database
run: a stopped install expires nobody until its next start, which expires them first.

## Backups

While Keycloak is on, `backup` takes its database too (its people, groups and one-time code
setups), and `restore` puts it back: see
[Backing up and restoring Kilnmark](../backup-and-restore.md). Keycloak keeps compressed copies of
its pages: after changing the theme in `containers/keycloak/themes`, recreate it with `restart`.
