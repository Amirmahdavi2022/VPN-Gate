#!/usr/bin/env python3
"""
Pulls the public VPN Gate server list and writes servers.json for the app.

Two sources are merged:
  - the HTML table on vpngate.net, which is the only place that says which
    servers actually have MS-SSTP enabled and on which port
  - the CSV API, which carries score / ping / speed / IP per host

Servers without SSTP are dropped. If the HTML parse finds nothing (layout
changed, site hiccup) we fall back to CSV-only with ports guessed from the
OpenVPN config, and mark the file so the app knows the ports are guesses.
"""
import base64
import html
import json
import re
import sys
import time
import urllib.request

UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36"
HTML_URLS = ["https://www.vpngate.net/en/", "http://www.vpngate.net/en/"]
CSV_URLS = ["https://www.vpngate.net/api/iphone/", "http://www.vpngate.net/api/iphone/"]


def get(urls):
    last = None
    for u in urls:
        for _ in range(3):
            try:
                req = urllib.request.Request(u, headers={"User-Agent": UA})
                with urllib.request.urlopen(req, timeout=40) as r:
                    return r.read().decode("utf-8", "replace")
            except Exception as e:  # noqa
                last = e
                time.sleep(3)
    print(f"fetch failed: {last}", file=sys.stderr)
    return ""


def parse_csv(text):
    rows = {}
    for line in text.splitlines():
        if not line or line[0] in "*#":
            continue
        p = line.split(",")
        if len(p) < 15:
            continue
        name = p[0].strip()
        try:
            item = {
                "name": name,
                "ip": p[1].strip(),
                "score": int(p[2] or 0),
                "ping": int(p[3] or 0),
                "speed": int(p[4] or 0),
                "country": p[5].strip(),
                "cc": p[6].strip().upper(),
                "sessions": int(p[7] or 0),
                "uptime": int(p[8] or 0),
            }
        except ValueError:
            continue
        # OpenVPN config is the last column; pull the TCP port out of it as a fallback
        port = None
        try:
            cfg = base64.b64decode(p[-1]).decode("utf-8", "replace")
            if re.search(r"^\s*proto\s+tcp", cfg, re.M):
                m = re.search(r"^\s*remote\s+\S+\s+(\d+)", cfg, re.M)
                if m:
                    port = int(m.group(1))
        except Exception:  # noqa
            pass
        item["ovpn_tcp_port"] = port
        rows[name.lower()] = item
    return rows


def parse_html_sstp(text):
    """Returns {short_name: (full_host, port)} for every row that lists an SSTP hostname."""
    found = {}
    # strip tags but keep a separator so tokens don't glue together
    flat = html.unescape(re.sub(r"<[^>]+>", " ", text))
    for m in re.finditer(r"SSTP\s+Hostname\s*:?\s*([A-Za-z0-9.-]+\.opengw\.net)(?::(\d+))?", flat):
        host = m.group(1).lower()
        port = int(m.group(2)) if m.group(2) else 443
        short = host.split(".")[0]
        found[short] = (host, port)
    return found


def main():
    out_path = sys.argv[1] if len(sys.argv) > 1 else "servers.json"
    csv_rows = parse_csv(get(CSV_URLS))
    sstp = parse_html_sstp(get(HTML_URLS))
    print(f"csv rows: {len(csv_rows)}  html sstp hosts: {len(sstp)}")

    servers = []
    mode = "html"
    if sstp:
        for short, (host, port) in sstp.items():
            row = csv_rows.get(short, {})
            servers.append({
                "h": host,
                "p": port,
                "ip": row.get("ip", ""),
                "cc": row.get("cc", ""),
                "c": row.get("country", ""),
                "s": row.get("score", 0),
                "ping": row.get("ping", 0),
                "spd": row.get("speed", 0),
                "ses": row.get("sessions", 0),
            })
    else:
        mode = "csv-guess"
        for row in csv_rows.values():
            servers.append({
                "h": f"{row['name'].lower()}.opengw.net",
                "p": row["ovpn_tcp_port"] or 443,
                "ip": row["ip"],
                "cc": row["cc"],
                "c": row["country"],
                "s": row["score"],
                "ping": row["ping"],
                "spd": row["speed"],
                "ses": row["sessions"],
            })

    servers.sort(key=lambda s: s["s"], reverse=True)
    if not servers:
        print("no servers parsed, keeping previous file", file=sys.stderr)
        sys.exit(1)

    with open(out_path, "w") as f:
        json.dump({"v": 1, "t": int(time.time()), "mode": mode, "servers": servers},
                  f, separators=(",", ":"))
    print(f"wrote {len(servers)} servers ({mode}) to {out_path}")


if __name__ == "__main__":
    main()
