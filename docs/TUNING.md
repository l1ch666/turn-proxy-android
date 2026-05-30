# Performance tuning (Android client)

The full‑tunnel data path is: device apps → TUN (sing‑box) → VLESS → local
vk‑turn core (`127.0.0.1:<localPort>`) → smux/KCP/DTLS → TURN → server.

VK throttles **~5 Mbit/s per TURN stream**; aggregate speed comes from running
several parallel streams (multi‑session is the default). Always measure end‑to‑end
(iperf3/speedtest through the tunnel) and change one knob at a time.

## Knobs

- **TUN MTU** — `FullTunnelConfig.tunMtu` (default **1280**). The inner MTU must
  leave room for VLESS/TLS + smux + KCP + DTLS + TURN overhead. Try raising toward
  ~1340 and re‑measure; lower it if you see loss.
- **KCP / smux** (core‑side) — `-kcp-window`, `-kcp-interval`, `-kcp-mtu`,
  `-smux-recvbuf`, `-smux-streambuf`, plus env `VK_TURN_KCP_*` / `VK_TURN_SMUX_*`.
  On Android pass them through the client **raw command** mode, or via the proxy
  service env. Keep `-kcp-mtu` ≤ the TUN MTU.
- **`-n`** — parallel TURN streams (main throughput multiplier).
- **`-vless-bond`** — experimental; usually **slower** than multi‑session
  (head‑of‑line coupling across paths). A/B it before using.

See the core repo's `docs/TUNING.md` for the full methodology, the KCP‑window↔BDP
relationship, and bond‑vs‑multi‑session details.
