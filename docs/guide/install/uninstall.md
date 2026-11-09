# Uninstalling

| | Linux | Windows |
| --- | --- | --- |
| Remove the program, keep the data | `sudo /opt/kilnmark/kilnmark.sh uninstall` | `"C:\Program Files\Kilnmark\kilnmark.cmd" uninstall`, from a prompt that is not elevated |
| Remove everything | `sudo /opt/kilnmark/kilnmark.sh uninstall --purge` | `"C:\Program Files\Kilnmark\kilnmark.cmd" uninstall -Purge` |

`uninstall`, in steps that are each OK or FAILED:

1. **Stops Kilnmark and removes its hourly task or timer**, as the operator. With `--purge` it also
   deletes the compose project's volumes (Kilnmark's database, the key to stored passwords, the
   files folder and Keycloak's database) and its images; an image another install on the machine
   still uses is kept, and named.
2. **Removes the program folder**, as root or an administrator (Windows asks, UAC).
3. With `--purge`, **removes the data folder**: its settings, secrets, answers, logs and every
   backup in it.

On Linux, `uninstall` runs as root, and first refuses a program or data folder with a folder above it
that anyone but root may change (not root's, a link, or writable by its group or others, `/tmp`
too), before it reads anything in the data folder: someone who could swap such a folder for a link
could have root write, change or remove wherever they liked. It says which folder. Make each folder
above it root's and writable by root alone (`sudo chown root:root FOLDER && sudo chmod go-w
FOLDER`), or, with Kilnmark stopped, move the data folder under one that is, as `/var/opt` is, and
write its new path in the program folder's `data-folder.txt`; then run `uninstall` again.

`--purge` asks you to type `DELETE` first (or takes it as one line on standard input). Back up
first, somewhere other than the data folder, if you might want any of it.

Without `--purge`, the volumes and the data folder stay. Install refuses a data folder that is
there already, so to install afresh, either `uninstall --purge` first, or give the new install
another data folder. Restore a backup kept elsewhere into a new install of the same mode
([Backing up and restoring Kilnmark](../backup-and-restore.md)).

On Linux the account that ran Kilnmark is kept, as it may be someone's own: `sudo userdel -r
kilnmark` removes it, with its home folder and its rootless Podman's images. On Windows the
Podman machine is the operator's, and stays.
