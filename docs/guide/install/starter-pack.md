# A Starter Pack install

The **Starter Pack** is for trials, demonstrations and training: example data, every sample ready
to run, and five example people to sign in as, one per role
([ADR 0051](../../adr/0051-starter-pack.md)). It works only on a **Starter Pack install**, which is
never for real data and never becomes a production one.

## Install it

Answer `starter-pack` to the first question, or give `--starter` (`-Starter`):

| | Linux | Windows |
| --- | --- | --- |
| Asking the questions | `sudo ./kilnmark.sh install --starter` | `kilnmark.cmd install -Starter` |
| From an answers file | `sudo ./kilnmark.sh install --answers install.conf` | `kilnmark.cmd install -Answers install.conf` |

The answers for a Starter Pack install, on one machine:

```
mode=starter-pack
email_domain=training.example
host=localhost
tls=off
admin_email=trainer@training.example
admin_first_name=Terry
admin_last_name=Trainer
```

A Starter Pack install always signs in with the bundled Keycloak, where its example people are:
install does not ask, and Kilnmark does not start otherwise. Everything else is asked as for any
install ([INSTALL.md](../../../INSTALL.md#the-questions)): for a class on other machines, give
the host name they use and `tls=proxy`, with a proxy that ends TLS in front of Kilnmark and
Keycloak ([From other machines](production-bundled-keycloak.md#from-other-machines)). Install adds the first admin you name, and turns
the Starter Pack on as its last step. Every page then shows "Starter Pack: training and development
only".

![What the Starter Pack adds: on Kilnmark's PostgreSQL server, Kilnmark's own database and the separate kilnmark_samples database, whose own login reads and makes nothing in Kilnmark's; the pack's folder in the files folder; and, in Keycloak, five example people made by keycloak-setup with Keycloak's own admin, and no Keycloak credential in Kilnmark](images/starter-pack.svg)

## Turn it on and off

Install turns it on. Afterwards, an admin opens **Settings**, **Starter Pack** and chooses **Turn
on**. Kilnmark then, by itself:

- adds a database source on `kilnmark_samples` and a files source on `/srv/files/starter-pack`,
  both allowing writing;
- creates the sample tables there (the `sample-tables` pipeline) and adds every sample to the
  **Library**, reading them;
- adds to the marking that the example data and accounts are on.

The page shows each step and how it went. A source called `starter-samples-db` or
`starter-samples-files` that the pack did not make, your own, is left as it is: the page says so.

To see Kilnmark used for a whole business process, try the **business scenarios** in the Library:
customer onboarding, a supplier's file arriving and the month-end close, each with several
pipelines, a flow and a data steward's part. [Business scenarios](../scenarios.md) walks through
each with the example people below: who builds, who reviews, who sees the results and who sees
sensitive values unmasked.

**Turn it off** under **Settings**, **Starter Pack**: choose **Turn off** and type `DELETE`.
Kilnmark removes every sample in the Library, the pack's sources, the tables in `kilnmark_samples`
(the database stays, empty) and the files in `/srv/files/starter-pack`. Nothing else changes, and
the install stays a Starter Pack install. The example people stay until you remove them. Both
turning on and off are in the audit log, and the access review marks kilnmark.com accounts as
example accounts.

## Its database

A Starter Pack install has an empty database, `kilnmark_samples`, on Kilnmark's own PostgreSQL
server, with a login of its own, `kilnmark_samples`, whose password is
`KILNMARK_SAMPLES_DB_PASSWORD` in `config/.env` (install makes one). The database is closed to
everyone but that login when it is made, and PostgreSQL itself (`pg_hba.conf`) lets that login
reach its own database only, over TLS. The `samples-db` service makes or checks both at every
start, once Kilnmark's own record says this is a Starter Pack install, then stops (`logs
samples-db` says what it did); it changes no other database's grants. Kilnmark's backups do not
hold `kilnmark_samples`: after a restore, turn the pack off and on again to recreate the samples.

## The example people

Install sets `KILNMARK_STARTER=allowed` in `config/.env`. Then, as the operator:

| | Linux | Windows |
| --- | --- | --- |
| Add them | `kilnmark.sh starter people` | `kilnmark.cmd starter people` |
| Give them all a new password | `kilnmark.sh starter people --new-password` | `kilnmark.cmd starter people -NewPassword` |
| Remove them | `kilnmark.sh starter people --remove` | `kilnmark.cmd starter people -Remove` |

(On Linux, run each as the account: `sudo -iu kilnmark /opt/kilnmark/kilnmark.sh starter people`.)

`keycloak-setup`, with Keycloak's own admin, adds `vera.lane` (viewer), `sam.okafor` (steward),
`dana.reyes` (designer), `priya.shah` (designer, sees sensitive values) and `alex.morgan` (admin),
all `@kilnmark.com`, which receives no mail from here, in the Keycloak group
`kilnmark-starter-pack`, which carries a mark only `keycloak-setup` sets. They share one password,
which you type twice, not shown, or pipe in on one line (12 characters or more). It goes to
`keycloak-setup` on its input only: never on the screen, in `config/.env`, a container's settings,
a command line, `logs` or Kilnmark. Adding them again keeps the password of anyone already there.
Someone already in Keycloak with one of those addresses that the pack did not make is left as they
are, and a group of that name that `keycloak-setup` did not make (from a directory, say) stops it,
changing nothing. Each sets up a one-time code on first sign-in. Kilnmark's audit log records each
change (`starter.people`), and Keycloak records each as an admin event (its console, realm
**kilnmark**, **Events**, **Admin events**). Kilnmark holds no Keycloak credential, in any setting.
Anyone who has the password can sign in as an example admin or as a designer who sees sensitive
values: that is why they are only for trials and training.

To take them out, run `starter people --remove`, or remove `KILNMARK_STARTER=allowed` from
`config/.env` and run `up`: `keycloak-setup` then deletes the members of the pack's group whose user
name is one of the five example addresses (user names cannot change), and the group. Anyone else
someone put in the group is only taken out of it, and named; nobody outside it is touched.

## People of your own

A class signs in with people of your own too: `people add` for one, `people import FILE` for a
class from a CSV file, sharing one temporary password you type once. Training data is made up, so
a file may give `kilnmark-sensitive-data`. See
[Adding people to the bundled Keycloak](production-bundled-keycloak.md#adding-people-to-the-bundled-keycloak):
the commands are the same in both modes.

## Done with it

A Starter Pack install never becomes a production one: for real work, install Kilnmark afresh
([Choosing a deployment](choosing-a-deployment.md)), and take this one off with
`uninstall --purge` ([Uninstalling](uninstall.md)).
