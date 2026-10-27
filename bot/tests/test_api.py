import asyncio

from aiohttp.test_utils import TestClient, TestServer

from oda_bot.api import FAIL_LIMIT, KeyApi
from oda_bot.db import Db
from oda_bot.license import Signer, verify


def run(coro):
    return asyncio.run(coro)


async def with_client(tmp_path, fn):
    db = Db(":memory:")
    db.touch_user(1, "u")
    signer = Signer.load_or_create(tmp_path / "k")
    client = TestClient(TestServer(KeyApi(db, signer).app()))
    await client.start_server()
    try:
        return await fn(client, db, signer)
    finally:
        await client.close()


def test_activate_check_flow(tmp_path):
    async def fn(client, db, signer):
        key = db.create_key(1, "month", 1).key
        r = await client.post("/v1/activate", json={"key": key.lower().replace("-", " "),
                                                    "device_id": "android-1234abcd", "device_name": "Redmi"})
        body = await r.json()
        assert r.status == 200 and body["status"] == "ok" and body["days_left"] == 30
        lic = verify(body["license"], signer.public_b64())
        assert lic["key"] == key and lic["dev"] == "android-1234abcd" and lic["exp"] == body["expires_at"]
        r = await client.post("/v1/check", json={"key": key, "device_id": "android-1234abcd"})
        assert r.status == 200
        # Второе устройство на тарифе с одним — отказ с понятным текстом
        r = await client.post("/v1/activate", json={"key": key, "device_id": "android-other99",
                                                    "device_name": "Pixel"})
        body = await r.json()
        assert r.status == 409 and body["status"] == "device_limit" and "Сменить устройство" in body["message"]
        r = await client.get("/v1/pubkey")
        assert (await r.json())["key"] == signer.public_b64()
    run(with_client(tmp_path, fn))


def test_bad_input_and_bruteforce(tmp_path):
    async def fn(client, db, signer):
        r = await client.post("/v1/activate", json={"key": "ODA-AAAA-AAAA-AAAA", "device_id": "x"})
        assert r.status == 400  # плохой device_id
        for _ in range(FAIL_LIMIT):
            r = await client.post("/v1/activate", json={"key": "ODA-AAAA-AAAA-AAAA", "device_id": "android-1234abcd"})
            assert r.status == 404
        r = await client.post("/v1/activate", json={"key": "ODA-AAAA-AAAA-AAAA", "device_id": "android-1234abcd"})
        assert r.status == 429
    run(with_client(tmp_path, fn))
