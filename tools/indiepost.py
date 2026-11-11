"""IndiePost via its MCP endpoint (auth is injected by the session proxy).
usage: python3 indiepost.py <tool> '<json args>'"""
import json, sys, urllib.request
def call(tool, args):
    req = urllib.request.Request("https://www.indiepost.app/api/mcp", method="POST",
        data=json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": tool, "arguments": args}}).encode(),
        headers={"Content-Type": "application/json", "Accept": "application/json, text/event-stream"})
    body = urllib.request.urlopen(req, timeout=120).read().decode()
    for line in body.splitlines():
        if line.startswith("data:"): body = line[5:]
    r = json.loads(body)
    if "error" in r: raise SystemExit(json.dumps(r["error"], ensure_ascii=False))
    out = r["result"]
    txt = "".join(c.get("text", "") for c in out.get("content", []))
    try: return json.loads(txt)
    except Exception: return out.get("structuredContent") or txt
if __name__ == "__main__":
    print(json.dumps(call(sys.argv[1], json.loads(sys.argv[2]) if len(sys.argv) > 2 else {}), ensure_ascii=False, indent=1))
