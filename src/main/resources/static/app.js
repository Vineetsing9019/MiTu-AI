/* MiTu AI – web page logic: chat, documents and anime art. */
const $ = (s) => document.querySelector(s);
const store = {
  get(k) { try { return localStorage.getItem(k); } catch { return null; } },
  set(k, v) { try { localStorage.setItem(k, v); } catch { /* private mode */ } },
};

// ---------- theme (light / dark / device) ----------
const themeBtn = $('#theme');
const savedTheme = store.get('mitu-theme');
if (savedTheme) document.documentElement.dataset.theme = savedTheme;
themeBtn?.addEventListener('click', () => {
  const now = document.documentElement.dataset.theme;
  const dark = now === 'dark' || (!now && matchMedia('(prefers-color-scheme: dark)').matches);
  const next = dark ? 'light' : 'dark';
  document.documentElement.dataset.theme = next;
  store.set('mitu-theme', next);
});

// ---------- tabs ----------
document.querySelectorAll('.tab').forEach((t) =>
  t.addEventListener('click', () => {
    document.querySelectorAll('.tab').forEach((x) => x.classList.toggle('active', x === t));
    document.querySelectorAll('.panel').forEach((p) => p.classList.toggle('active', p.id === t.dataset.tab));
    store.set('mitu-tab', t.dataset.tab);
  })
);
const savedTab = store.get('mitu-tab');
if (savedTab) document.querySelector(`.tab[data-tab="${savedTab}"]`)?.click();

// ---------- status ----------
let aiOn = false;
async function loadStatus() {
  try {
    const s = await (await fetch('/api/status')).json();
    aiOn = s.aiOn;
    const pill = $('#status');
    pill.textContent = s.aiOn ? `● AI on · ${s.chatModel}` : '● Offline mode (no API key)';
    pill.className = 'pill ' + (s.aiOn ? 'on' : 'off');
    renderStyles(s.styles);
  } catch {
    $('#status').textContent = 'Server not reachable';
  }
}

// ---------- API helper ----------
/* Calls the server and returns its JSON. Turns every kind of failure into a sentence a person
   understands: Render's free plan sleeps and restarts, and then answers with an HTML page
   (502/503) instead of JSON. */
async function api(url, options = {}) {
  let res;
  try {
    res = await fetch(url, options);
  } catch {
    throw new Error('Can\'t reach MiTu. Check your internet, or wait a moment: the server may be waking up.');
  }
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : {}; } catch { /* not JSON: a proxy or error page */ }
  if (res.ok && data) return data;
  if (data && data.message) throw new Error(data.message);
  if ([502, 503, 504].includes(res.status)) throw new Error('MiTu is waking up or updating. Please try again in about a minute.');
  if (res.status === 413) throw new Error('That file is too big (20 MB max).');
  throw new Error(`Something went wrong (error ${res.status}). Please try again.`);
}

// ---------- chat ----------
let sessionId = store.get('mitu-session') || newId();
store.set('mitu-session', sessionId);
function newId() { return (crypto.randomUUID ? crypto.randomUUID() : String(Date.now() + Math.random())); }

const esc = (s) => s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
function markdown(text) {
  const lines = esc(text).split('\n');
  let html = '', list = false;
  for (const raw of lines) {
    const line = raw
      .replace(/\*\*(.+?)\*\*/g, '<b>$1</b>')
      .replace(/`([^`]+)`/g, '<code>$1</code>')
      .replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g, '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>')
      .replace(/(^|[\s(])(https?:\/\/[^\s<)]*[^\s<).,!?;:])/g,'$1<a href="$2" target="_blank" rel="noopener noreferrer">$2</a>')
      .replace(/(^|\s)_([^_]+)_(?=\s|$|[.,!?])/g, '$1<i>$2</i>');
    const head = line.match(/^\s*#{1,6}\s+(.*)/);
    if (head) { if (list) { html += '</ul>'; list = false; } html += `<p class="h"><b>${head[1]}</b></p>`; continue; }
    const item = line.match(/^\s*(?:[-*•]|\d+\.)\s+(.*)/);
    if (item) { if (!list) { html += '<ul>'; list = true; } html += `<li>${item[1]}</li>`; continue; }
    if (list) { html += '</ul>'; list = false; }
    if (line.trim()) html += `<p>${line}</p>`;
  }
  return html + (list ? '</ul>' : '');
}

function addMsg(who, html, save = true) {
  const row = document.createElement('div');
  row.className = `msg ${who}`;
  row.innerHTML = `<div class="bubble">${html}</div>`;
  $('#messages').appendChild(row);
  $('#messages').scrollTop = $('#messages').scrollHeight;
  if (save && !html.includes('class="typing"')) saveMsg(who, html);
  return row;
}

/* The conversation is kept in this browser (localStorage), so a refresh doesn't lose it.
   Only finished messages are saved; the last 60 are kept. */
const CHAT_KEY = 'mitu-chat';
function savedChat() {
  try { return JSON.parse(store.get(CHAT_KEY) || '[]'); } catch { return []; }
}
function saveMsg(who, html) {
  const list = savedChat();
  list.push({ who, html });
  store.set(CHAT_KEY, JSON.stringify(list.slice(-60)));
}
function restoreChat() {
  const list = savedChat();
  if (!list.length) return false;
  $('#messages').innerHTML = '';
  for (const m of list) addMsg(m.who, m.html, false);
  return true;
}

/* Shows the answer's text appearing word by word, then swaps in the formatted version.
   It only takes about a second, so it feels live without a streaming server. */
function reveal(bubble, text) {
  return new Promise((resolve) => {
    const plain = document.createElement('p');
    bubble.innerHTML = '';
    bubble.appendChild(plain);
    const words = text.split(/(\s+)/);
    const step = Math.max(1, Math.ceil(words.length / 60));
    let i = 0;
    const timer = setInterval(() => {
      i += step;
      plain.textContent = words.slice(0, i).join('');
      $('#messages').scrollTop = $('#messages').scrollHeight;
      if (i >= words.length) { clearInterval(timer); resolve(); }
    }, 18);
  });
}

async function ask(question) {
  addMsg('user', esc(question));
  const typing = addMsg('bot', '<span class="typing"><span></span><span></span><span></span></span>', false);
  $('#send').disabled = true;
  try {
    const data = await api('/api/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ question, sessionId }),
    });
    const bubble = typing.querySelector('.bubble');
    if (data.answer.length < 4000) await reveal(bubble, data.answer);
    let html = markdown(data.answer);
    if (data.sources?.length) {
      html += '<div class="sources">' + data.sources
        .map((s) => `<span class="source" title="${esc(s.snippet)}">[${s.n}] ${esc(s.document)} · p.${s.page}</span>`)
        .join('') + '</div>';
    }
    if (data.image) {
      html += `<div class="gen"><div class="gen-busy"><div class="spinner"></div>Painting your picture… (about 10 s)</div>
        <img alt="Picture made by MiTu" src="${esc(data.image)}"></div>
        <a class="secondary small-btn" href="${esc(data.image)}" target="_blank" rel="noopener">Open full size</a>`;
    }
    if (data.web?.length) {
      html += '<div class="sources">' + data.web
        .map((w) => `<a class="source web" href="${esc(w.url)}" target="_blank" rel="noopener noreferrer">🌐 ${esc(w.title)}</a>`)
        .join('') + '</div>';
    }
    const label = {
      ai: data.web?.length ? 'Answered by AI from your documents and the web' : 'Answered by AI from your documents',
      web: 'Answered by AI with a web search',
      general: 'Answered by AI',
      image: 'Picture made with AI (free: Pollinations.ai)',
      offline: 'Offline answer: best matching passages',
    };
    html += `<span class="mode">${label[data.mode] || ''}</span>`;
    bubble.innerHTML = html;
    saveMsg('bot', html);
    const img = bubble.querySelector('.gen img');
    if (img) {
      const busy = bubble.querySelector('.gen-busy');
      img.addEventListener('load', () => { busy.remove(); $('#messages').scrollTop = $('#messages').scrollHeight; });
      img.addEventListener('error', () => { busy.textContent = '⚠ The free picture service is busy. Ask me again in a moment.'; img.remove(); });
    }
  } catch (e) {
    typing.querySelector('.bubble').textContent = '⚠ ' + e.message;
  } finally {
    $('#send').disabled = false;
    $('#messages').scrollTop = $('#messages').scrollHeight;
  }
}

$('#ask').addEventListener('submit', (e) => {
  e.preventDefault();
  const q = $('#question').value.trim();
  if (!q) return;
  $('#question').value = '';
  autoGrow();
  ask(q);
});
$('#question').addEventListener('keydown', (e) => {
  if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); $('#ask').requestSubmit(); }
});
function autoGrow() { const t = $('#question'); t.style.height = 'auto'; t.style.height = Math.min(160, t.scrollHeight) + 'px'; }
$('#question').addEventListener('input', autoGrow);

const mic = $('#mic');
const Speech = window.SpeechRecognition || window.webkitSpeechRecognition;
if (mic && Speech) {
  const rec = new Speech();
  rec.lang = 'en-IN';
  rec.interimResults = true;
  rec.continuous = false;
  let base = '';
  rec.onresult = (e) => {
    let text = '';
    for (const r of e.results) text += r[0].transcript;
    $('#question').value = (base + ' ' + text).trim();
    autoGrow();
  };
  rec.onend = () => { mic.classList.remove('listening'); mic.setAttribute('aria-pressed', 'false'); };
  rec.onerror = () => { mic.classList.remove('listening'); };
  mic.addEventListener('click', () => {
    if (mic.classList.contains('listening')) { rec.stop(); return; }
    base = $('#question').value;
    mic.classList.add('listening');
    mic.setAttribute('aria-pressed', 'true');
    rec.start();
  });
} else if (mic) {
  mic.hidden = true;
}
$('#suggest').addEventListener('click', (e) => { if (e.target.tagName === 'BUTTON') ask(e.target.textContent); });
$('#newChat').addEventListener('click', async () => {
  await fetch(`/api/chat/${encodeURIComponent(sessionId)}`, { method: 'DELETE' }).catch(() => {});
  sessionId = newId();
  store.set('mitu-session', sessionId);
  $('#messages').innerHTML = '';
  store.set(CHAT_KEY, '[]');
  addMsg('bot', 'New chat started. Ask me anything about your documents ✨');
});

// ---------- documents ----------
async function loadDocs() {
  const list = $('#docList');
  try {
    const docs = await api('/api/documents');
    if (!docs.length) { list.innerHTML = '<li class="empty">No documents yet.</li>'; return; }
    list.innerHTML = '';
    for (const d of docs) {
      const li = document.createElement('li');
      li.innerHTML = `<span>📄</span><span class="doc-name" title="${esc(d.name)}">${esc(d.name)}</span>
        <span class="doc-meta">${d.pages} pages · ${d.chunks} chunks</span>
        <span class="badge">${d.smartSearch ? 'AI search' : 'Keyword search'}</span>
        <button class="icon-btn" title="Remove">✕</button>`;
      li.querySelector('button').addEventListener('click', async () => {
        if (!confirm(`Remove "${d.name}" from MiTu?`)) return;
        await fetch(`/api/documents/${d.id}`, { method: 'DELETE' });
        loadDocs();
      });
      list.appendChild(li);
    }
  } catch {
    list.innerHTML = '<li class="empty">Could not load documents.</li>';
  }
}

/* Uploads a PDF. Shows progress in the Documents tab, and in the chat when attached from there. */
async function uploadPdf(file, fromChat = false) {
  if (!file) return;
  const note = (t) => { $('#uploading').hidden = false; $('#uploading').textContent = t; };
  if (!/\.pdf$/i.test(file.name) && file.type !== 'application/pdf') {
    const msg = 'Please choose a PDF file.';
    fromChat ? addMsg('bot', '⚠ ' + msg) : note('⚠ ' + msg);
    return;
  }
  const status = fromChat ? addMsg('bot', `📄 Reading <b>${esc(file.name)}</b>…`) : null;
  note(`Reading and indexing "${file.name}"…`);
  const form = new FormData();
  form.append('file', file);
  try {
    const data = await api('/api/documents', { method: 'POST', body: form });
    const done = `✅ "${data.name}" is ready: ${data.pages} page${data.pages === 1 ? '' : 's'}, ${data.chunks} chunks.`;
    note(done + ' Ask about it in Chat.');
    if (status) status.querySelector('.bubble').innerHTML = esc(done) + ' Ask me anything about it 👇';
    loadDocs();
  } catch (e) {
    note('⚠ ' + e.message);
    if (status) status.querySelector('.bubble').textContent = '⚠ ' + e.message;
  }
}
// Reset each input after use, so choosing the same file again still uploads it.
$('#pdf').addEventListener('change', (e) => { uploadPdf(e.target.files[0]); e.target.value = ''; });
$('#chatPdf').addEventListener('change', (e) => { uploadPdf(e.target.files[0], true); e.target.value = ''; });
dropZone($('#drop'), uploadPdf);
dropZone($('#ask'), (f) => uploadPdf(f, true));

function dropZone(el, onFile) {
  el.addEventListener('dragover', (e) => { e.preventDefault(); el.classList.add('over'); });
  el.addEventListener('dragleave', () => el.classList.remove('over'));
  el.addEventListener('drop', (e) => { e.preventDefault(); el.classList.remove('over'); onFile(e.dataTransfer.files[0]); });
}

// ---------- anime art ----------
let style = 'ANIME';
let artMode = 'ai'; // 'ai' = AI repaint on the server, 'face' = the real photo through the in-browser filter
document.querySelectorAll('.mode-btn').forEach((b) => b.addEventListener('click', () => {
  artMode = b.dataset.mode;
  document.querySelectorAll('.mode-btn').forEach((x) => {
    x.classList.toggle('active', x === b);
    x.setAttribute('aria-checked', String(x === b));
  });
}));
let photoFile = null;

function renderStyles(styles) {
  const box = $('#styles');
  box.innerHTML = '';
  for (const s of styles) {
    const b = document.createElement('button');
    b.className = 'style' + (s.id === style ? ' active' : '');
    b.textContent = s.label;
    b.addEventListener('click', () => {
      style = s.id;
      box.querySelectorAll('.style').forEach((x) => x.classList.toggle('active', x === b));
    });
    box.appendChild(b);
  }
}

/* Every photo (gallery, drop or camera) is redrawn as a JPEG of at most 1600 px, so big phone
   photos upload fast and any format the browser can show is accepted. */
async function choosePhoto(file) {
  if (!file) return;
  $('#artNote').textContent = '';
  let jpeg;
  try {
    const img = await createImageBitmap(file);
    const scale = Math.min(1, 1600 / Math.max(img.width, img.height));
    const c = document.createElement('canvas');
    c.width = Math.round(img.width * scale);
    c.height = Math.round(img.height * scale);
    c.getContext('2d').drawImage(img, 0, 0, c.width, c.height);
    const blob = await new Promise((r) => c.toBlob(r, 'image/jpeg', 0.9));
    jpeg = new File([blob], (file.name || 'photo').replace(/\.[^.]+$/, '') + '.jpg', { type: 'image/jpeg' });
  } catch {
    $('#artNote').textContent = "This browser can't open that picture. Try a JPG or PNG photo.";
    return;
  }
  photoFile = jpeg;
  $('#before').src = URL.createObjectURL(jpeg);
  $('#after').removeAttribute('src');
  $('#download').hidden = true;
  $('#makeArt').disabled = false;
}

// ---------- camera ----------
let camStream = null;
function closeCamera() {
  camStream?.getTracks().forEach((t) => t.stop());
  camStream = null;
  $('#cam').hidden = true;
}
$('#useCamera').addEventListener('click', async () => {
  // Phones: the camera app via the file input. Laptops: a live webcam preview.
  const phone = /Android|iPhone|iPad|iPod/i.test(navigator.userAgent);
  if (phone || !navigator.mediaDevices?.getUserMedia) { $('#cameraInput').click(); return; }
  try {
    camStream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'user', width: { ideal: 1280 } }, audio: false });
    $('#camVideo').srcObject = camStream;
    $('#cam').hidden = false;
  } catch {
    $('#artNote').textContent = 'Camera blocked or not found. Allow camera access in the browser (🔒 next to the address), or choose a photo instead.';
  }
});
$('#camCancel').addEventListener('click', closeCamera);
$('#cam').addEventListener('click', (e) => { if (e.target.id === 'cam') closeCamera(); });
$('#camSnap').addEventListener('click', () => {
  const v = $('#camVideo');
  if (v.videoWidth < 16) { $('#artNote').textContent = 'The camera is still starting. Try again in a second.'; return; }
  const c = document.createElement('canvas');
  c.width = v.videoWidth;
  c.height = v.videoHeight;
  const ctx = c.getContext('2d');
  ctx.translate(c.width, 0); // save it the way the user saw it (mirrored, like a selfie)
  ctx.scale(-1, 1);
  ctx.drawImage(v, 0, 0);
  c.toBlob((blob) => { closeCamera(); choosePhoto(new File([blob], 'camera.jpg', { type: 'image/jpeg' })); }, 'image/jpeg', 0.92);
});
$('#cameraInput').addEventListener('change', (e) => { choosePhoto(e.target.files[0]); e.target.value = ''; });
$('#photo').addEventListener('change', (e) => { choosePhoto(e.target.files[0]); e.target.value = ''; });
dropZone($('#photoDrop'), choosePhoto);

$('#makeArt').addEventListener('click', async () => {
  if (!photoFile) return;
  $('#makeArt').disabled = true;
  $('#artBusy').hidden = false;
  $('#artNote').textContent = '';
  try {
    let blob = null;
    if (aiOn && artMode === 'ai') {
      const form = new FormData();
      form.append('photo', photoFile);
      form.append('style', style);
      const res = await fetch('/api/art', { method: 'POST', body: form });
      if (res.ok) {
        blob = await res.blob();
        $('#artNote').textContent = res.headers.get('X-Mitu-Engine') === 'free'
          ? '✨ Free AI art: Gemini described your photo, then Pollinations.ai painted it in this style. (A new painting of the scene, not an edit of your exact photo.)'
          : '✨ Painted by the AI image model from your photo.';
      } else {
        const err = await res.json().catch(() => ({}));
        $('#artNote').textContent = `${err.message || 'AI art failed.'} Used MiTu's offline filter instead.`;
      }
    }
    if (!blob) {
      blob = await offlineArt(photoFile, style);
      if (artMode === 'face') $('#artNote').textContent = '🙂 Your real photo, styled in the browser: your face stays exactly the same.';
      else if (!aiOn) $('#artNote').textContent = "Made with MiTu's offline filter. Add a Gemini key for full AI art.";
    }
    const url = URL.createObjectURL(blob);
    $('#after').src = url;
    $('#download').href = url;
    $('#download').hidden = false;
  } catch (e) {
    $('#artNote').textContent = '⚠ ' + e.message;
  } finally {
    $('#artBusy').hidden = true;
    $('#makeArt').disabled = false;
  }
});

/* Offline art: image processing in the browser, so the feature works with no AI key.
   Smooths the photo, reduces it to flat colour bands, warms and brightens it, then
   draws ink outlines found with a Sobel edge detector. */
async function offlineArt(file, styleId) {
  const img = await createImageBitmap(file);
  const max = 1024;
  const scale = Math.min(1, max / Math.max(img.width, img.height));
  const w = Math.round(img.width * scale), h = Math.round(img.height * scale);
  const canvas = document.createElement('canvas');
  canvas.width = w; canvas.height = h;
  const ctx = canvas.getContext('2d', { willReadFrequently: true });

  if (styleId === 'PIXEL') {
    const small = document.createElement('canvas');
    const pw = 96, ph = Math.max(1, Math.round(96 * h / w));
    small.width = pw; small.height = ph;
    small.getContext('2d').drawImage(img, 0, 0, pw, ph);
    const sctx = small.getContext('2d');
    const d = sctx.getImageData(0, 0, pw, ph);
    posterize(d.data, 5); saturate(d.data, 1.3, 1.05);
    sctx.putImageData(d, 0, 0);
    ctx.imageSmoothingEnabled = false;
    ctx.drawImage(small, 0, 0, w, h);
    return toBlob(canvas);
  }

  ctx.drawImage(img, 0, 0, w, h);
  const src = ctx.getImageData(0, 0, w, h);
  const gray = toGray(src.data, w, h);
  const out = new ImageData(new Uint8ClampedArray(src.data), w, h);
  const p = {
    ANIME: { blur: 5, radius: 3, levels: 8, sat: 1.3, light: 1.1, edge: 14, ink: 0.8, warm: 8 },
    WATERCOLOR: { blur: 6, radius: 3, levels: 10, sat: 1.15, light: 1.12, edge: 0, ink: 0, warm: 6 },
    COMIC: { blur: 4, radius: 2, levels: 4, sat: 1.6, light: 1.0, edge: 10, ink: 1, warm: 0 },
  }[styleId] || { blur: 5, radius: 3, levels: 8, sat: 1.3, light: 1.1, edge: 14, ink: 0.8, warm: 8 };

  if (styleId === 'SKETCH') {
    const soft = new ImageData(new Uint8ClampedArray(src.data), w, h);
    boxBlur(soft.data, w, h, 1);
    const edges = sobel(toGray(soft.data, w, h), w, h);
    for (let i = 0; i < w * h; i++) {
      const v = 255 - Math.min(255, edges[i] * 6);
      const shade = 0.75 * v + 0.25 * (gray[i] > 140 ? 255 : gray[i] + 60);
      const n = (Math.random() - 0.5) * 14;
      out.data[i * 4] = out.data[i * 4 + 1] = out.data[i * 4 + 2] = shade + n;
    }
  } else {
    // Smooth first (flat painted areas), then find outlines on the smooth picture so noise
    // and skin texture don't turn into lines.
    for (let k = 0; k < p.blur; k++) boxBlur(out.data, w, h, p.radius);
    const smoothEdges = sobel(toGray(out.data, w, h), w, h);
    posterize(out.data, p.levels);
    saturate(out.data, p.sat, p.light, p.warm);
    if (p.edge) {
      const edges = smoothEdges;
      for (let i = 0; i < w * h; i++) {
        if (edges[i] > p.edge) {
          const a = p.ink * Math.min(1, (edges[i] - p.edge) / 20 + 0.35);
          for (let c = 0; c < 3; c++) out.data[i * 4 + c] = out.data[i * 4 + c] * (1 - a) + 30 * a;
        }
      }
    }
    if (styleId === 'WATERCOLOR') {
      for (let i = 0; i < out.data.length; i += 4) {
        const n = (Math.random() - 0.5) * 18;
        out.data[i] += n; out.data[i + 1] += n; out.data[i + 2] += n;
      }
    }
  }
  ctx.putImageData(out, 0, 0);
  return toBlob(canvas);
}

function toBlob(canvas) { return new Promise((r) => canvas.toBlob(r, 'image/png')); }
function toGray(d, w, h) {
  const g = new Float32Array(w * h);
  for (let i = 0; i < w * h; i++) g[i] = 0.299 * d[i * 4] + 0.587 * d[i * 4 + 1] + 0.114 * d[i * 4 + 2];
  return g;
}
function sobel(g, w, h) {
  const e = new Float32Array(w * h);
  for (let y = 1; y < h - 1; y++) {
    for (let x = 1; x < w - 1; x++) {
      const i = y * w + x;
      const gx = -g[i - w - 1] - 2 * g[i - 1] - g[i + w - 1] + g[i - w + 1] + 2 * g[i + 1] + g[i + w + 1];
      const gy = -g[i - w - 1] - 2 * g[i - w] - g[i - w + 1] + g[i + w - 1] + 2 * g[i + w] + g[i + w + 1];
      e[i] = Math.sqrt(gx * gx + gy * gy) / 4;
    }
  }
  return e;
}
function boxBlur(d, w, h, r) {
  const tmp = new Uint8ClampedArray(d.length);
  for (let pass = 0; pass < 2; pass++) {
    const from = pass === 0 ? d : tmp, to = pass === 0 ? tmp : d;
    for (let y = 0; y < h; y++) {
      for (let x = 0; x < w; x++) {
        let rs = 0, gs = 0, bs = 0, n = 0;
        for (let k = -r; k <= r; k++) {
          const xx = pass === 0 ? Math.min(w - 1, Math.max(0, x + k)) : x;
          const yy = pass === 0 ? y : Math.min(h - 1, Math.max(0, y + k));
          const j = (yy * w + xx) * 4;
          rs += from[j]; gs += from[j + 1]; bs += from[j + 2]; n++;
        }
        const i = (y * w + x) * 4;
        to[i] = rs / n; to[i + 1] = gs / n; to[i + 2] = bs / n; to[i + 3] = from[i + 3];
      }
    }
  }
}
function posterize(d, levels) {
  if (!levels) return;
  const step = 255 / (levels - 1);
  for (let i = 0; i < d.length; i += 4) for (let c = 0; c < 3; c++) d[i + c] = Math.round(d[i + c] / step) * step;
}
function saturate(d, sat, light, warm = 0) {
  for (let i = 0; i < d.length; i += 4) {
    const avg = (d[i] + d[i + 1] + d[i + 2]) / 3;
    d[i] = (avg + (d[i] - avg) * sat) * light + warm;
    d[i + 1] = (avg + (d[i + 1] - avg) * sat) * light + warm * 0.4;
    d[i + 2] = (avg + (d[i + 2] - avg) * sat) * light - warm * 0.3;
  }
}

restoreChat();
loadStatus();
loadDocs();
