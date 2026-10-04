#!/bin/sh
systemctl daemon-reload >/dev/null 2>&1 || true
systemctl enable frkn.service >/dev/null 2>&1 || true
systemctl restart frkn.service >/dev/null 2>&1 || true
