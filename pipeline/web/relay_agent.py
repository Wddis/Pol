"""Bridge a fixed list of Cloudflare commands to the local montage panel."""
import json
import os
from pathlib import Path
import re
import subprocess
import time
from urllib.request import Request, urlopen
from urllib.error import HTTPError

import server

EDGE = os.environ["T2X2_RELAY_URL"].rstrip("/")
TOKEN = os.environ["T2X2_RELAY_SECRET"]
if not re.fullmatch(r"https://[a-z0-9-]+\.[a-z0-9-]+\.workers\.dev", EDGE):
    raise RuntimeError("Некорректный адрес Worker.")


def exchange(path, body):
    config = "\n".join(
        f"{name} = {json.dumps(value,ensure_ascii=False)}"
        for name,value in (("url",EDGE+path),("header","Authorization: Bearer "+TOKEN),
                           ("header","Content-Type: application/json"),
                           ("data-binary",json.dumps(body,ensure_ascii=False)))
    )
    result=subprocess.run(["curl","--silent","--show-error","--fail","--max-time","15","--config","-"],
                          input=config,text=True,capture_output=True,check=True,timeout=17)
    return json.loads(result.stdout)


def perform(command):
    method,path=command["method"],command["path"]
    if method=="GET" and path=="/ping":
        return {"status":200,"body":{"ok":True}}
    if method=="GET" and re.fullmatch(r"/api/catalog\?page=[1-9][0-9]{0,2}",path):
        data=None
    elif method=="POST" and (path=="/api/jobs" or
                              re.fullmatch(r"/api/jobs/[a-f0-9]{32}/(render|retry|short|metadata|editor)",path)):
        data=json.dumps(command["body"],ensure_ascii=False).encode()
        metadata = path.endswith("/metadata") or (
            path.endswith("/render") and isinstance(command["body"], dict) and "metadata_edit" in command["body"]
        )
        if len(data) > (65536 if metadata else 8192):
            return {"status":413,"body":{"detail":"Слишком большое задание."}}
    else:
        return {"status":400,"body":{"detail":"Неизвестная команда."}}
    if method == "POST" and path.endswith("/render") and isinstance(command["body"], dict):
        question = command["body"].get("editor_question")
        if question is not None:
            path = path.removesuffix("/render") + "/editor"
            data = json.dumps(question, ensure_ascii=False).encode()
        edit = command["body"].get("metadata_edit")
        if edit is not None:
            path = path.removesuffix("/render") + "/metadata"
            data = json.dumps(edit, ensure_ascii=False).encode()
    req=Request("http://127.0.0.1:8787"+path,data=data,method=method,
                headers={"Content-Type":"application/json","X-T2X2-Panel":"1","X-T2X2-Command":command["id"]})
    try:
        with urlopen(req,timeout=80) as response:
            return {"status":response.status,"body":json.load(response)}
    except HTTPError as exc:
        return {"status":exc.code,"body":json.load(exc)}
    except Exception as exc:
        raise RuntimeError("Локальная панель не ответила.") from exc


snapshot_cursor=0


def collect():
    global snapshot_cursor
    states=[]
    paths = sorted((p for p in server.DATA.glob("*/job.json") if server.visible_job(p)),key=lambda p:p.stat().st_mtime,reverse=True)
    older = paths[12:]
    batch = paths[:12] + older[snapshot_cursor:snapshot_cursor + 12]
    snapshot_cursor = snapshot_cursor + 12 if snapshot_cursor + 12 < len(older) else 0
    for path in batch:
        state=server.state_for(path.parent)
        state["changed"] = int(path.stat().st_mtime * 1000)
        state["separate_delivery"]=True
        state["proposals"]=state.get("proposals",[])[:25]
        if "qa" in state:
            state["qa"]["speech"] = [{"hits_count":len(x["hits"]), "timestamps":[[hit["start"],hit["end"]] for hit in x["hits"][:30]]}
                                      for x in state["qa"].get("speech") or []]
        states.append(state)
        if len(json.dumps(states,ensure_ascii=False).encode())>400000:
            states.pop()
            break
    return states


if __name__=="__main__":
    pending=None
    while True:
        try:
            if pending:
                exchange("/agent/result",pending)
                pending=None
            command=exchange("/agent/poll",{"states":collect()})["command"]
            if command:
                pending={"id":command["id"],"result":perform(command)}
                continue
        except Exception as exc:
            print(type(exc).__name__,flush=True)
        time.sleep(5)
