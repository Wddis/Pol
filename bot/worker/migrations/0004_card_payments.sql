-- 0.12: оплата картой / СБП через Cashera — ссылка на платёжную страницу и её срок (повторный показ «Платёж уже сформирован»)
ALTER TABLE orders ADD COLUMN pay_url TEXT;
ALTER TABLE orders ADD COLUMN expires_at INTEGER;
