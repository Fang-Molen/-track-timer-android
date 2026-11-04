#!/bin/bash
# 跑道计时中转：IP 直连部署（无 nginx）。不碰 3210 / kaogong / docker。
set -euo pipefail
C="$1"; B="https://raw.githubusercontent.com/Fang-Molen/-track-timer-android/$C/server"
ss -ltn | grep -q ':8790 ' && { echo "ABORT: 8790 in use"; exit 1; }
id tt-relay >/dev/null 2>&1 || useradd --system --no-create-home --shell /usr/sbin/nologin tt-relay
mkdir -p /opt/track-timer-relay
curl -fsSL "$B/relay.mjs" -o /opt/track-timer-relay/relay.mjs
sha256sum /opt/track-timer-relay/relay.mjs
cat > /etc/systemd/system/track-timer-relay.service <<'U'
[Unit]
Description=Track timer relay (IP mode)
After=network.target
[Service]
User=tt-relay
Group=tt-relay
Environment=PORT=8790 HOST=0.0.0.0 WS_PATH=/tt-relay TRUST_PROXY=0
ExecStart=/usr/bin/node /opt/track-timer-relay/relay.mjs
Restart=always
RestartSec=2
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true
MemoryMax=128M
[Install]
WantedBy=multi-user.target
U
systemctl daemon-reload
systemctl enable --now track-timer-relay
sleep 2
systemctl is-active track-timer-relay
ss -ltn | grep ':8790 '
ss -ltn | grep -q ':3210 ' && echo "3210 still OK"
systemctl is-active kaogong-app kaogong-gate
echo "ROLLBACK: systemctl disable --now track-timer-relay; rm -rf /opt/track-timer-relay /etc/systemd/system/track-timer-relay.service"
echo DONE
