"""BrightBean via its MCP endpoint (Bearer key is injected by the session proxy).
usage: python3 brightbean.py <tool> '<json args>'    |  python3 brightbean.py --list"""
import json, sys, urllib.request
URL = "https://api-platform.brightbean.xyz/api/v1/mcp"
H = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream", "Mcp-Protocol-Version": "2025-06-18", "User-Agent": "curl/8.5.0"}
def rpc(method, params=None):
    req = urllib.request.Request(URL, method="POST", headers=H,
        data=json.dumps({"jsonrpc": "2.0", "id": 1, "method": method, "params": params or {}}).encode())
    body = urllib.request.urlopen(req, timeout=180).read().decode()
    for line in body.splitlines():
        if line.startswith("data:"): body = line[5:]
    r = json.loads(body)
    if "error" in r: raise SystemExit(json.dumps(r["error"], ensure_ascii=False))
    return r["result"]
def call(tool, args):
    out = rpc("tools/call", {"name": tool, "arguments": args})
    if out.get("structuredContent"): return out["structuredContent"]
    txt = "".join(c.get("text", "") for c in out.get("content", []))
    for cand in (txt, txt[txt.find('{'):] if '{' in txt else ''):
        try: return json.loads(cand)
        except Exception: pass
    return txt
if __name__ == "__main__":
    if sys.argv[1] == "--list":
        print("\n".join(t["name"] for t in rpc("tools/list")["tools"]))
    else:
        print(json.dumps(call(sys.argv[1], json.loads(sys.argv[2]) if len(sys.argv) > 2 else {}), ensure_ascii=False, indent=1))
