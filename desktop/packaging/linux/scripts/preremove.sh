#!/bin/sh
case "$1" in
    upgrade|1) exit 0 ;;
esac
systemctl disable --now frkn.service >/dev/null 2>&1 || true
