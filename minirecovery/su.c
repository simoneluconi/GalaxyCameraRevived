/*
 * Minimal su for the gd1 mini recovery.
 *
 * The stock 4.1.2 adbd is a "user" build, so it always drops to the shell
 * uid whatever ro.secure says. This setuid-root helper gives the adb shell a
 * root shell. It is only ever installed in the mini recovery ramdisk.
 *
 * Usage: su            interactive root shell
 *        su -c "cmd"   run one command as root
 */
#include <grp.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

int main(int argc, char **argv)
{
    if (setgid(0) || setgroups(0, NULL) || setuid(0)) {
        perror("su");
        return 1;
    }
    if (argc > 2 && strcmp(argv[1], "-c") == 0)
        execl("/sbin/sh", "sh", "-c", argv[2], (char *)NULL);
    else
        execl("/sbin/sh", "sh", (char *)NULL);
    perror("su: exec /sbin/sh");
    return 1;
}
