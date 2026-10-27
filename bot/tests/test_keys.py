from oda_bot.keys import ALPHABET, generate_key, normalize, plural, price


def test_generate_format():
    keys = {generate_key() for _ in range(2000)}
    assert len(keys) == 2000  # без повторов
    for k in list(keys)[:50]:
        prefix, *groups = k.split("-")
        assert prefix == "ODA" and len(groups) == 3
        assert all(len(g) == 4 and all(c in ALPHABET for c in g) for g in groups)


def test_normalize_user_input():
    assert normalize("ODA-7K2Q-M9XD-4TPA") == "ODA-7K2Q-M9XD-4TPA"
    assert normalize(" oda 7k2q m9xd 4tpa ") == "ODA-7K2Q-M9XD-4TPA"
    assert normalize("7K2QM9XD4TPA") == "ODA-7K2Q-M9XD-4TPA"
    assert normalize("ODA-7K2Q-M9XD-4TP") is None      # короткий
    assert normalize("ODA-7K2Q-M9XD-4TP0") is None     # 0 нет в алфавите
    assert normalize("") is None


def test_price_devices():
    assert price(300, 1, 50) == 300
    assert price(300, 2, 50) == 450
    assert price(300, 5, 50) == 900
    assert price(300, 9, 50) == 900  # больше 5 не бывает
    assert price(0, 3, 50) == 0


def test_plural():
    assert [plural(n, "день", "дня", "дней") for n in (1, 2, 5, 11, 21, 22, 25, 111)] == \
        ["день", "дня", "дней", "дней", "день", "дня", "дней", "дней"]
