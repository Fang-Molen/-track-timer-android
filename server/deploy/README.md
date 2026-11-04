# 跑道计时中转服务器 · 部署说明（尚未部署）

只转发两台手机的短消息（对时探测、发令状态），不保存、不上传视频，不写消息日志。房间 30 分钟无活动自动删除，进程重启后全部清空。

## 前提（部署前要确认，均待核）
1. 能重新登录阿里云服务器（目前登录已过期），由用户本人登录。
2. 用户明确同意在这台机器上加这个服务（它上面跑着公考 App）。
3. 服务器上有 Node 18 或以上：`node -v`。
4. 端口 8790 空闲且不是 3210：`ss -ltnp | grep -E ':8790|:3210'`；再确认 kaogong-app、kaogong-gate 用的端口不是 8790。旧的 dianji-relay 如果装过，用的是 8787，两者不冲突（待核是否装过）。
5. 有一个已配好 HTTPS 证书的域名（复用现有 nginx 证书），或新建子域名并申请证书。手机端只接受 `wss://`。
6. 不需要在阿里云安全组新开端口（8790 只对本机开放，外部走 443）。

## 步骤（需要用户确认后才执行）
```bash
sudo useradd --system --no-create-home --shell /usr/sbin/nologin tt-relay
sudo install -d /opt/track-timer-relay
sudo install -m 0644 relay.mjs /opt/track-timer-relay/relay.mjs
sudo install -m 0644 deploy/track-timer-relay.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now track-timer-relay
curl -fsS http://127.0.0.1:8790/healthz      # 应输出 ok
# 把 deploy/nginx-tt-relay.conf 的 location 加进现有 HTTPS server 块
sudo nginx -t && sudo systemctl reload nginx
```
手机上填写：`wss://你的域名/tt-relay`（也可在构建时用 `-PtrackTimerRelayUrl=wss://…/tt-relay` 预置）。

## 回滚
```bash
sudo systemctl disable --now track-timer-relay
sudo rm /etc/systemd/system/track-timer-relay.service && sudo systemctl daemon-reload
# 删除 nginx 中的 /tt-relay location，然后 sudo nginx -t && sudo systemctl reload nginx
```

## 本地测试
`node --test test/*.test.mjs`；Android 端的联调测试 `RelayIntegrationTest` 会在本机自动启动 relay.mjs。
