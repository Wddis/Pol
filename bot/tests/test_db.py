from oda_bot.db import DAY, Db, Result

T0 = 1_800_000_000


def make():
    db = Db(":memory:")
    db.touch_user(1, "u", T0)
    return db


def test_activation_starts_timer():
    db = make()
    k = db.create_key(1, "month", 2, T0)
    assert k.activated_at is None and k.expires_at is None and k.days == 30
    res, k2 = db.activate(k.key, "device-aaaa", "Redmi", T0 + 5 * DAY)
    assert res == Result.OK
    assert k2.activated_at == T0 + 5 * DAY  # отсчёт с активации, а не с покупки
    assert k2.expires_at == T0 + 35 * DAY


def test_device_limit_and_reset():
    db = make()
    k = db.create_key(1, "week", 2, T0)
    assert db.activate(k.key, "device-aaaa", "A", T0)[0] == Result.OK
    assert db.activate(k.key, "device-bbbb", "B", T0)[0] == Result.OK
    assert db.activate(k.key, "device-aaaa", "A", T0)[0] == Result.OK  # то же устройство — не новое место
    assert db.activate(k.key, "device-cccc", "C", T0)[0] == Result.DEVICE_LIMIT
    assert db.reset_devices(k.key) == 2
    assert db.check(k.key, "device-aaaa", T0)[0] == Result.DEVICE_REMOVED
    assert db.activate(k.key, "device-cccc", "C", T0)[0] == Result.OK


def test_expiry_and_extend_same_key():
    db = make()
    k = db.create_key(1, "week", 1, T0)
    db.activate(k.key, "device-aaaa", "A", T0)
    assert db.check(k.key, "device-aaaa", T0 + 6 * DAY)[0] == Result.OK
    assert db.check(k.key, "device-aaaa", T0 + 7 * DAY)[0] == Result.EXPIRED
    # Продление после окончания — с текущей минуты
    k2 = db.extend(k.key, 30, T0 + 10 * DAY)
    assert k2.expires_at == T0 + 40 * DAY
    assert db.check(k.key, "device-aaaa", T0 + 11 * DAY)[0] == Result.OK
    # Продление действующего — к оставшемуся сроку
    k3 = db.extend(k.key, 7, T0 + 11 * DAY)
    assert k3.expires_at == T0 + 47 * DAY


def test_extend_before_activation_adds_days():
    db = make()
    k = db.create_key(1, "week", 1, T0)
    db.extend(k.key, 30, T0)
    _, k2 = db.activate(k.key, "device-aaaa", "A", T0 + DAY)
    assert k2.expires_at == T0 + DAY + 37 * DAY


def test_unknown_and_trial_flag():
    db = make()
    assert db.activate("ODA-AAAA-AAAA-AAAA", "device-aaaa", "A", T0)[0] == Result.NOT_FOUND
    assert not db.trial_used(1)
    db.mark_trial_used(1)
    assert db.trial_used(1)
