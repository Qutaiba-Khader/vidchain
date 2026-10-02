#!/usr/bin/env bash
# Judge the emulator capture and write only DERIVED lists (hosts), never the capture itself (Q14).
#  - both canaries present (the capture covered the whole run, otherwise a clean result means nothing)
#  - no developer / tracker / geolocation host at any time
#  - no ad / attribution host while VidChain was installed and running (between the canaries; the
#    emulator image's own Play services look up ad hosts on their own before that)
# usage: analyse_traffic.sh <capture.pcap>
set -u
PCAP=${1:?usage: analyse_traffic.sh <capture.pcap>}
OUT=runtime-evidence
mkdir -p "$OUT"
[ -s "$PCAP" ] || { echo "FAIL: no capture file"; exit 1; }

tshark -r "$PCAP" -Y 'dns.flags.response == 0' -T fields -e dns.qry.name 2>/dev/null | sort -u > "$OUT/dns-queries.txt"
tshark -r "$PCAP" -Y 'tls.handshake.extensions_server_name' -T fields -e tls.handshake.extensions_server_name 2>/dev/null | sort -u > "$OUT/tls-hosts.txt"
tshark -r "$PCAP" -Y 'http.request' -T fields -e http.host 2>/dev/null | sort -u > "$OUT/http-hosts.txt"
echo "== hosts looked up during the run"; cat "$OUT/dns-queries.txt"
echo "== TLS connections";               cat "$OUT/tls-hosts.txt"
echo "== plain HTTP hosts";              cat "$OUT/http-hosts.txt"

for c in vidchain-canary-start.example.com vidchain-canary-end.example.com; do
  grep -qix "$c" "$OUT/dns-queries.txt" \
    || { echo "FAIL: canary $c missing - the capture did not record the whole run"; exit 1; }
done
bad=$(cat "$OUT"/dns-queries.txt "$OUT"/tls-hosts.txt "$OUT"/http-hosts.txt | grep -Ei 'tubeaio|back4app|parseapi|supabase|ip-api|ipapi|ipinfo|ipwho|geoip|ipgeolocation' | sort -u)
if [ -n "$bad" ]; then echo "FAIL: developer/tracker host contacted:"; echo "$bad"; exit 1; fi
tshark -r "$PCAP" -Y 'dns.flags.response == 0' -T fields -e frame.time_epoch -e dns.qry.name 2>/dev/null |
  awk 'tolower($2)=="vidchain-canary-start.example.com" && !s {s=1; next}
       tolower($2)=="vidchain-canary-end.example.com" {exit}
       s {print $2}' | sort -u > "$OUT/app-window-dns.txt"
echo "== looked up while VidChain was installed and running"; cat "$OUT/app-window-dns.txt"
ads=$(grep -Ei 'googlesyndication|googleadservices|doubleclick|admob|adservice|applovin|applvn|unityads|unity3d|ironsrc|ironsource|supersonicads|pangle|pangolin|bytedance|byteoversea|mbridge|mintegral|rayjump|vungle|chartboost|inmobi|appodeal|adcolony|startappservice|an\.facebook|ads\.|adsrv|adnxs|moatads|appsflyer|adjust\.com|branch\.io' "$OUT/app-window-dns.txt" | sort -u)
if [ -n "$ads" ]; then echo "FAIL: ad / attribution host looked up while the app ran:"; echo "$ads"; exit 1; fi
echo "PASS: capture covers the whole run (both canaries), no developer/tracker host, no ad host while the app ran"
