// JavaScript snippets executed inside the page (Runtime.evaluate).
// Interactive elements get a numeric `data-mcp-ref` attribute so that
// click/type can address them by number.

const INTERACTIVE = [
  'a[href]', 'button', 'input', 'textarea', 'select', 'summary', 'label[for]',
  '[role=button]', '[role=link]', '[role=tab]', '[role=menuitem]', '[role=checkbox]',
  '[role=radio]', '[role=option]', '[role=combobox]', '[role=textbox]', '[role=switch]',
  '[role=searchbox]', '[contenteditable=""]', '[contenteditable=true]', '[onclick]',
].join(',');

const FIND = (ref) => `document.querySelector('[data-mcp-ref="${Number(ref)}"]')`;
const NOT_FOUND = (ref) => `{ok:false,error:'Element [${Number(ref)}] not found: the page changed. Take a new snapshot.'}`;

export const SNAPSHOT_JS = (maxChars, maxElements) => `(() => {
  const SEL = ${JSON.stringify(INTERACTIVE)};
  const clean = (s, n = 80) => (s || '').replace(/\\s+/g, ' ').trim().slice(0, n);
  const visible = (el) => {
    if (el.checkVisibility && !el.checkVisibility({ checkOpacity: true, checkVisibilityCSS: true })) return false;
    const r = el.getBoundingClientRect();
    return r.width > 1 && r.height > 1;
  };
  const name = (el) => {
    const by = el.getAttribute('aria-labelledby');
    if (by) { const t = by.split(/\\s+/).map(i => document.getElementById(i)?.innerText || '').join(' '); if (t.trim()) return clean(t); }
    if (el.getAttribute('aria-label')) return clean(el.getAttribute('aria-label'));
    if (el.labels && el.labels[0]) return clean(el.labels[0].innerText);
    const t = clean(el.innerText);
    if (t) return t;
    const img = el.querySelector && el.querySelector('img[alt]');
    return clean(el.getAttribute('placeholder') || el.getAttribute('title') || (img && img.alt) || el.getAttribute('name') || el.getAttribute('alt') || '');
  };
  document.querySelectorAll('[data-mcp-ref]').forEach(e => e.removeAttribute('data-mcp-ref'));
  const lines = [];
  let n = 0, total = 0;
  if (${maxElements} > 0) for (const el of document.querySelectorAll(SEL)) {
    if (el.type === 'hidden' || !visible(el)) continue;
    const parent = el.parentElement && el.parentElement.closest('[data-mcp-ref]');
    if (parent && (parent.tagName === 'A' || parent.tagName === 'BUTTON')) continue;
    total++;
    if (n >= ${maxElements}) continue;
    n++;
    el.setAttribute('data-mcp-ref', n);
    const tag = el.tagName.toLowerCase();
    const role = el.getAttribute('role');
    let kind = role || tag;
    if (tag === 'input') kind = 'input:' + (el.type || 'text');
    let line = '[' + n + '] ' + kind;
    const nm = name(el);
    if (nm) line += ' "' + nm + '"';
    if (tag === 'input' || tag === 'textarea') {
      if (el.type === 'password') line += el.value ? ' value=(hidden)' : ' (empty)';
      else if (el.type === 'checkbox' || el.type === 'radio') line += el.checked ? ' [checked]' : ' [unchecked]';
      else line += el.value ? ' value="' + clean(el.value, 60) + '"' : ' (empty)';
    } else if (tag === 'select') {
      line += ' selected="' + clean(el.selectedOptions[0]?.text || '', 40) + '" options=' + el.options.length;
    } else if (tag === 'a') {
      const h = el.getAttribute('href') || '';
      if (h && !h.startsWith('javascript:')) line += ' -> ' + clean(h, 70);
    }
    if (el.disabled) line += ' (disabled)';
    if (el.getAttribute('aria-expanded')) line += ' expanded=' + el.getAttribute('aria-expanded');
    lines.push(line);
  }
  let text = (document.body ? document.body.innerText : '') || '';
  text = text.replace(/[ \\t]+/g, ' ').replace(/\\n\\s*\\n+/g, '\\n').trim();
  const truncated = text.length > ${maxChars};
  return {
    url: location.href,
    title: document.title,
    scroll: Math.round(scrollY) + '/' + Math.max(0, Math.round(document.documentElement.scrollHeight - innerHeight)),
    elements: lines.join('\\n') + (total > n ? '\\n... ' + (total - n) + ' more elements (scroll or raise maxElements)' : ''),
    text: text.slice(0, ${maxChars}) + (truncated ? '\\n... [text truncated, ' + text.length + ' chars total]' : ''),
  };
})()`;

export const CLICK_JS = (ref) => `(() => {
  const el = ${FIND(ref)};
  if (!el) return ${NOT_FOUND(ref)};
  el.scrollIntoView({ block: 'center', inline: 'center' });
  if (el.tagName === 'A') {
    const href = el.getAttribute('href') || '';
    const scheme = (href.match(/^\s*([a-z][a-z0-9+.-]*):/i) || [])[1];
    if (scheme && !/^(https?|javascript)$/i.test(scheme)) {
      return { ok: false, error: 'This link opens something outside the browser (' + scheme + ':) and is blocked.' };
    }
    if (el.target === '_blank') el.target = '_self';
  }
  const r = el.getBoundingClientRect();
  const o = { bubbles: true, cancelable: true, view: window, clientX: r.x + r.width / 2, clientY: r.y + r.height / 2 };
  try { el.dispatchEvent(new PointerEvent('pointerdown', o)); } catch {}
  el.dispatchEvent(new MouseEvent('mousedown', o));
  if (el.focus) el.focus();
  try { el.dispatchEvent(new PointerEvent('pointerup', o)); } catch {}
  el.dispatchEvent(new MouseEvent('mouseup', o));
  el.click();
  return { ok: true, label: (el.innerText || el.value || el.getAttribute('aria-label') || el.tagName).trim().slice(0, 60) };
})()`;

export const FOCUS_JS = (ref, clear) => `(() => {
  const el = ${FIND(ref)};
  if (!el) return ${NOT_FOUND(ref)};
  el.scrollIntoView({ block: 'center' });
  el.focus();
  const isField = el.tagName === 'INPUT' || el.tagName === 'TEXTAREA';
  if (!isField && !el.isContentEditable) return { ok: false, error: 'Element [${Number(ref)}] is not a text field.' };
  if (${!!clear}) {
    if (isField) {
      const proto = el.tagName === 'INPUT' ? HTMLInputElement.prototype : HTMLTextAreaElement.prototype;
      Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, '');
      el.dispatchEvent(new Event('input', { bubbles: true }));
    } else {
      el.textContent = '';
    }
  } else if (isField) {
    try { el.setSelectionRange(el.value.length, el.value.length); } catch {}
  }
  return { ok: true, password: el.type === 'password', label: (el.getAttribute('aria-label') || el.placeholder || el.name || el.tagName).slice(0, 60) };
})()`;

export const READ_VALUE_JS = (ref) => `(() => {
  const el = ${FIND(ref)};
  if (!el) return null;
  return el.isContentEditable ? el.innerText : el.value;
})()`;

export const FALLBACK_TYPE_JS = (ref, text, clear) => `(() => {
  const el = ${FIND(ref)};
  if (!el) return false;
  const t = ${JSON.stringify(text)};
  el.focus();
  if (el.isContentEditable) {
    if (!document.execCommand('insertText', false, t)) el.textContent = (${!!clear} ? '' : el.textContent) + t;
  } else {
    const proto = el.tagName === 'INPUT' ? HTMLInputElement.prototype : HTMLTextAreaElement.prototype;
    Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, (${!!clear} ? '' : el.value) + t);
  }
  el.dispatchEvent(new Event('input', { bubbles: true }));
  el.dispatchEvent(new Event('change', { bubbles: true }));
  return true;
})()`;

export const SCROLL_JS = (direction, ref) => `(() => {
  ${ref ? `const el = ${FIND(ref)}; if (!el) return ${NOT_FOUND(ref)}; el.scrollIntoView({ block: 'center' });` : `
  const d = ${JSON.stringify(direction)};
  const h = innerHeight * 0.85;
  if (d === 'down') scrollBy(0, h);
  else if (d === 'up') scrollBy(0, -h);
  else if (d === 'top') scrollTo(0, 0);
  else if (d === 'bottom') scrollTo(0, document.documentElement.scrollHeight);
  else return { ok: false, error: 'direction must be up, down, top or bottom' };`}
  return { ok: true, scroll: Math.round(scrollY) + '/' + Math.max(0, Math.round(document.documentElement.scrollHeight - innerHeight)) };
})()`;

export const WAIT_JS = (text, selector) => `(() => {
  ${selector ? `if (!document.querySelector(${JSON.stringify(selector)})) return false;` : ''}
  ${text ? `if (!(document.body && document.body.innerText.toLowerCase().includes(${JSON.stringify(String(text).toLowerCase())}))) return false;` : ''}
  return true;
})()`;
