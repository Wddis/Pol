"""Лицензия для приложения: подписанный сервером срок ключа на этом устройстве (Ed25519).

Формат — «<данные>.<подпись>», обе части base64url без «=»; данные — JSON:
{"v":1,"key":"ODA-…","dev":"<device_id>","tariff":"month","devices":2,"exp":<unix>,"iat":<unix>}.
Приложение хранит открытый ключ и проверяет подпись само: срок считает сервер, а не часы телефона,
и подделать лицензию без закрытого ключа нельзя.
"""
import base64
import json
import os
from pathlib import Path

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey, Ed25519PublicKey


def _b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def _unb64(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


class Signer:
    def __init__(self, private: Ed25519PrivateKey):
        self.private = private
        self.public = private.public_key()

    @classmethod
    def load_or_create(cls, path: Path) -> "Signer":
        """Закрытый ключ — файл на сервере (32 байта); нет файла — создаётся один раз.
        Потерять его — значит выпустить новое приложение с новым открытым ключом."""
        if path.exists():
            return cls(Ed25519PrivateKey.from_private_bytes(path.read_bytes()))
        path.parent.mkdir(parents=True, exist_ok=True)
        key = Ed25519PrivateKey.generate()
        raw = key.private_bytes(serialization.Encoding.Raw, serialization.PrivateFormat.Raw,
                                serialization.NoEncryption())
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "wb") as f:
            f.write(raw)
        return cls(key)

    def public_b64(self) -> str:
        """Открытый ключ (32 байта, base64url) — вшивается в приложение."""
        return _b64(self.public.public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw))

    def sign(self, payload: dict) -> str:
        data = json.dumps(payload, separators=(",", ":"), sort_keys=True).encode()
        return _b64(data) + "." + _b64(self.private.sign(data))


def verify(token: str, public_b64: str) -> dict | None:
    """То же, что делает приложение: данные лицензии, если подпись верна, иначе None."""
    try:
        data_b64, sig_b64 = token.split(".")
        data = _unb64(data_b64)
        Ed25519PublicKey.from_public_bytes(_unb64(public_b64)).verify(_unb64(sig_b64), data)
        return json.loads(data)
    except (ValueError, InvalidSignature):
        return None
