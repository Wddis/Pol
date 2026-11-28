import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import time

from fastapi import HTTPException
from pydantic import ValidationError

import server
from access import AccessGate
from starlette.requests import Request
from starlette.responses import PlainTextResponse


class PanelTests(unittest.TestCase):
    def test_public_mode_requires_all_secrets(self):
        with self.assertRaises(RuntimeError):
            AccessGate({"T2X2_PUBLIC":"1"})

    def test_cookie_cannot_cross_accounts_and_attempts_are_limited(self):
        gate=AccessGate({"T2X2_PUBLIC":"1","T2X2_ACCESS_TEAM":"test.cloudflareaccess.com",
                         "T2X2_ACCESS_AUD":"example","T2X2_PUBLIC_HOST":"montage.example.org",
                         "T2X2_ACCESS_PIN":"test-only","T2X2_SESSION_KEY":"x"*32})
        payload=f"{int(time.time())+60}.nonce"
        cookie=f"{payload}.{gate.signature('first',payload)}"
        self.assertTrue(gate.valid_cookie("first",cookie))
        self.assertFalse(gate.valid_cookie("other",cookie))
        self.assertFalse(gate.valid_cookie("first",cookie+"x"))
        self.assertIsNone(gate.identity(""))
        self.assertEqual([gate.allow_attempt("first") for _ in range(6)],[True]*5+[False])

    def test_invalid_intervals_and_channel(self):
        for kwargs in (dict(vod="live",start=2),dict(vod="12",start=4,end=3),
                       dict(vod="https://other.example/")):
            with self.assertRaises(ValidationError):
                server.Analysis(**kwargs)

    def test_classification_keeps_absolute_source_time(self):
        result=server.proposals([dict(start=4,end=22,text="Смотрим новый ролик")],600)
        self.assertEqual((result[0]["start"],result[0]["end"],result[0]["kind"]),(604,622,"reaction"))

    def test_held_master_not_served(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(server,"DATA",Path(temp)):
            key="a"*32
            folder=Path(temp)/key
            (folder/"montage").mkdir(parents=True)
            server.save(folder/"job.json",dict(id=key,stage="На проверке"))
            server.save(folder/"montage/run.json",dict(status="held"))
            with self.assertRaises(HTTPException) as error:
                server.file(key,"master")
            self.assertEqual(error.exception.status_code,409)


class AccessTests(unittest.IsolatedAsyncioTestCase):
    async def test_two_gates_protect_download(self):
        gate=AccessGate({"T2X2_PUBLIC":"1","T2X2_ACCESS_TEAM":"test.cloudflareaccess.com",
                         "T2X2_ACCESS_AUD":"application","T2X2_PUBLIC_HOST":"montage.example.org",
                         "T2X2_ACCESS_PIN":"test-only","T2X2_SESSION_KEY":"x"*32})
        async def downstream(_):
            return PlainTextResponse("protected")
        def request(cookie=None, token=None):
            headers=[(b"host",b"montage.example.org")]
            if token:
                headers.append((b"cf-access-jwt-assertion",token.encode()))
            if cookie:
                headers.append((b"cookie",cookie.encode()))
            return Request({"type":"http","method":"GET","scheme":"https",
                "path":"/api/jobs/test/file/master","headers":headers,"query_string":b""})
        self.assertEqual((await gate(request(),downstream)).status_code,401)
        payload=f"{int(time.time())+60}.nonce"
        cookie=f"__Host-t2x2-session={payload}.{gate.signature('first',payload)}"
        with patch.object(gate,"identity",side_effect=lambda token: "first" if token=="verified" else None):
            self.assertEqual((await gate(request(cookie),downstream)).status_code,401)
            self.assertEqual((await gate(request(token="verified"),downstream)).status_code,401)
            response=await gate(request(cookie,"verified"),downstream)
            self.assertEqual(response.status_code,200)
            self.assertEqual(response.headers["Cache-Control"],"private, no-store")


class QueueTests(unittest.TestCase):
    def test_duplicate_command_and_recovery(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(server, "DATA", Path(temp)):
            cmd = "f" * 32
            request = server.Analysis(vod="123", start=20, end=40)
            self.assertEqual(server.start(request, cmd), server.start(request, cmd))
            self.assertEqual(len(list(Path(temp).glob("*/job.json"))), 1)
            state_path = Path(temp) / cmd / "job.json"
            state = server.read(state_path)
            state["operation"]["status"] = "running"
            state["stage"] = "Расшифровка"
            server.save(state_path, state)
            server.recover_jobs()
            self.assertEqual(server.read(state_path)["operation"]["status"], "queued")
            self.assertEqual(server.read(state_path)["operation"]["recipe"]["start"], 20)

    def test_interrupted_live_requires_confirmation(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(server, "DATA", Path(temp)):
            cmd = "e" * 32
            server.start(server.Analysis(vod="live"), cmd)
            path = Path(temp) / cmd / "job.json"
            state = server.read(path)
            state["operation"]["status"] = "running"
            server.save(path, state)
            server.recover_jobs()
            self.assertEqual(server.read(path)["stage"], "Прямой эфир прерван")
            self.assertEqual(server.read(path)["operation"]["status"], "interrupted")

    def test_same_vod_range_reuses_existing_job(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(server, "DATA", Path(temp)):
            source = server.Analysis(vod="123", start=20, end=40)
            first = server.start(source, "b" * 32)
            self.assertEqual(server.start(source, "c" * 32), first)
            self.assertEqual(len(list(Path(temp).glob("*/job.json"))), 1)

    def test_technical_and_duplicate_jobs_are_hidden(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(server, "DATA", Path(temp)):
            technical = next(iter(server.TECHNICAL_JOBS))
            for key, extra in ((technical, {}), ("b" * 32, {"duplicate_of": "c" * 32}), ("c" * 32, {})):
                path = Path(temp) / key
                path.mkdir()
                server.save(path / "job.json", {"id": key, "stage": "На проверке", **extra})
            self.assertEqual([s["id"] for s in server.jobs()], ["c" * 32])

    def test_metadata_edit_preserves_master_and_uses_output_clock(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(server, "DATA", Path(temp)):
            key = "f" * 32
            folder = Path(temp) / key
            output = folder / "montage"
            output.mkdir(parents=True)
            master = output / "master.mp4"
            master.write_bytes(b"fixture")
            server.save(folder / "job.json", {"id": key, "stage": "Готово"})
            server.save(output / "run.json", {"status": "ready", "deliverables": {"master": str(master)}})
            server.save(output / "metadata.json", {"title": "Старое", "tags": ["t2x2"]})
            server.save(output / "qa.json", {"master": {"duration": 35}})
            invalid = server.MetadataEdit(title="Новое", description="Описание", chapters=[(0,"Начало"),(28,"Финал")])
            with self.assertRaises(HTTPException):
                server.edit_metadata(key, invalid)
            valid = server.MetadataEdit(title="Новое", description="Описание", chapters=[(0,"Начало"),(15,"Эпизод")])
            server.edit_metadata(key, valid)
            metadata = server.state_for(folder)["metadata"]
            self.assertEqual((metadata["title"],metadata["tags"]),("Новое",["t2x2"]))
            self.assertEqual(master.read_bytes(),b"fixture")

    def test_render_keeps_source_coordinates_and_rejects_overlaps(self):
        fields = dict(start=10, end=60, kind="game", title="Эпизод", confirmed=True)
        render = server.Render(**fields, omit=[dict(start=15,end=17),dict(start=35,end=38)],
                               mute=[dict(start=40,end=40.5)])
        self.assertEqual(render.intervals(), [[10,15],[17,35],[38,60]])
        with self.assertRaises(ValidationError):
            server.Render(**fields, omit=[dict(start=15,end=17),dict(start=16,end=18)])
        with self.assertRaises(ValidationError):
            server.Render(**fields, mute=[dict(start=40,end=42)])

    def test_render_rejects_cut_containing_transcribed_speech(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(server, "DATA", Path(temp)):
            key = "a"*32
            folder = Path(temp)/key
            folder.mkdir()
            server.save(folder/"job.json",dict(id=key, stage="Карта готова", start=100, end=155, proposals=[{}]))
            server.save(folder/"source.manifest.json",dict(offset=100))
            (folder/"words.jsonl").write_text('{"words":[{"start":12,"end":13}]}\n')
            request = server.Render(start=101,end=154,kind="game",title="Эпизод",confirmed=True,
                                    omit=[dict(start=110,end=114)])
            with self.assertRaises(HTTPException) as error:
                server.start_render(key,request,"b"*32)
            self.assertEqual(error.exception.status_code,400)

    def test_reaction_mode_preserves_verified_full_range(self):
        from editor import full_reactions
        picked = full_reactions([],6030,"2929609163",6030,6540)
        self.assertEqual((picked[0]["start"],picked[0]["end"]), (6042,6526.5))
        self.assertEqual(picked[0]["original"],"https://youtu.be/p169Re3Vy9I")
        self.assertEqual(full_reactions([],6030,"2929609163",6030,6300),[])

    def test_reaction_needs_an_opening_and_ending(self):
        from editor import full_reactions
        rows = [{"start":3,"end":7,"text":"Давайте посмотрим видео"}]
        self.assertEqual(full_reactions(rows,0,"111",0,250),[])
        rows.append({"start":180,"end":184,"text":"На этом всё, дальше у нас другое"})
        self.assertEqual([(x["start"],x["end"]) for x in full_reactions(rows,0,"111",0,250)],[(3,184)])
