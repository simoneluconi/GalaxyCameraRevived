# Source this before `m` for a GalaxyCameraRevived build:
#   source tools/build-env.sh
# It sets who and where the build says it was made (ro.build.user,
# ro.build.host) and the build number, instead of the local login, the PC's
# name and eng.<user>.<date>. The kernel's user and host are set in the
# device tree (BoardConfigCommon.mk).

export BUILD_USERNAME=simoneluconi
export BUILD_HOSTNAME=galaxycamerarevived
export BUILD_NUMBER="gcr.$(date +%Y%m%d.%H%M)"
