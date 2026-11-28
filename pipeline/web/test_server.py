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
