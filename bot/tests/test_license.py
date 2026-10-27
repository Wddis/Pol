from oda_bot.license import Signer, verify


def test_sign_and_verify(tmp_path):
    s = Signer.load_or_create(tmp_path / "k")
    token = s.sign({"v": 1, "key": "ODA-AAAA-AAAA-AAAA", "exp": 123})
    assert verify(token, s.public_b64()) == {"v": 1, "key": "ODA-AAAA-AAAA-AAAA", "exp": 123}
    # Тот же ключ после перезапуска (файл), чужой ключ подпись не принимает
    assert Signer.load_or_create(tmp_path / "k").public_b64() == s.public_b64()
    other = Signer.load_or_create(tmp_path / "other")
    assert verify(token, other.public_b64()) is None


def test_tampered_license_rejected(tmp_path):
    s = Signer.load_or_create(tmp_path / "k")
    data, sig = s.sign({"exp": 100}).split(".")
    forged = s.sign({"exp": 999999}).split(".")[0] + "." + sig  # срок подменён, подпись старая
    assert verify(forged, s.public_b64()) is None
    assert verify("garbage", s.public_b64()) is None
