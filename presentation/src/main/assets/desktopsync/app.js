// Desktop Sync dashboard. Talks to the DesktopSyncServer running on the
// Kompakt; the pairing token comes in via ?token= on the page URL and is
// reused for every API call and the WebSocket upgrade.

/* ── Theme ─────────────────────────────────────────────────────────────────────
 * auto | light | dark, persisted per browser. "auto" is resolved to a concrete
 * value here rather than left to a CSS media query, so an explicit choice always
 * beats the system setting without needing a specificity fight in the stylesheet. */
const THEMES = ['auto', 'light', 'dark'];
const darkQuery = window.matchMedia('(prefers-color-scheme: dark)');
let themePref = localStorage.getItem('theme') || 'auto';
if (!THEMES.includes(themePref)) themePref = 'auto';

function applyTheme() {
  const resolved = themePref === 'auto' ? (darkQuery.matches ? 'dark' : 'light') : themePref;
  document.documentElement.dataset.theme = resolved;
  const btn = document.getElementById('themeBtn');
  if (btn) btn.textContent = themePref === 'auto' ? 'Auto' : (themePref === 'dark' ? 'Dark' : 'Light');
}

document.getElementById('themeBtn').addEventListener('click', () => {
  themePref = THEMES[(THEMES.indexOf(themePref) + 1) % THEMES.length];
  localStorage.setItem('theme', themePref);
  applyTheme();
});

// Follow the OS live, but only while the preference actually is "auto"
darkQuery.addEventListener('change', () => { if (themePref === 'auto') applyTheme(); });
applyTheme();

/*
 * The token, from the URL if it is there, otherwise from whatever a pairing code got us
 * last time. Storing it means the bookmark is just the host -- no 24-character token to
 * copy, and no token sitting in browser history either.
 */
const STORED_TOKEN_KEY = 'kotozute.token';
function storedToken() {
  try { return localStorage.getItem(STORED_TOKEN_KEY) || ''; } catch { return ''; }
}
function rememberToken(t) {
  try { localStorage.setItem(STORED_TOKEN_KEY, t); } catch { /* private window: this session only */ }
}
let token = new URLSearchParams(location.search).get('token') || storedToken();
if (new URLSearchParams(location.search).get('token')) {
  rememberToken(token);
  // Take it back out of the address bar: a token in a URL ends up in history, and the
  // whole point of the code is not having to carry one around.
  try { history.replaceState(null, '', location.pathname); } catch { /* not fatal */ }
}

/* ── Installing this as an app ─────────────────────────────────────────────────
 * The manifest has to carry the token: an installed window opens at start_url with
 * no query string of its own, and without the token that is a refusal rather than a
 * dashboard. So the link is rewritten here, where the token is known.
 *
 * The Install button appears only when the browser offers it — Chromium fires
 * beforeinstallprompt, Firefox has no equivalent and installs through its own menu,
 * so there is nothing to show there rather than a button that lies. */
(function setUpInstall() {
  if (token) {
    const link = document.getElementById('manifest');
    if (link) link.href = '/manifest.webmanifest?token=' + encodeURIComponent(token);
  }

  let deferred = null;
  const installBtn = document.getElementById('installBtn');
  window.addEventListener('beforeinstallprompt', e => {
    e.preventDefault();
    deferred = e;
    if (installBtn) installBtn.hidden = false;
  });
  if (installBtn) {
    installBtn.addEventListener('click', async () => {
      if (!deferred) return;
      deferred.prompt();
      await deferred.userChoice;
      deferred = null;
      installBtn.hidden = true;
    });
  }
  window.addEventListener('appinstalled', () => { if (installBtn) installBtn.hidden = true; });

  /* Keeping this as an app on the computer. Three different answers, because the
   * platforms give three different ones — and none of them is the manifest, which no
   * browser will act on over plain HTTP.
   *
   * Linux gets a launcher file with this phone's address in it, which prefers a Chromium
   * browser in app mode and falls back to opening the default browser. Windows and macOS
   * get told where their own browser already hides the same feature: Chromium's "Create
   * shortcut / Install as app" makes a proper window over plain HTTP, unlike install
   * prompts. Safari's is "Add to Dock". */
  const launcherBtn = document.getElementById('launcherBtn');
  const panel = document.getElementById('launcherPanel');
  const agent = navigator.userAgent;
  const platform = (navigator.userAgentData && navigator.userAgentData.platform) || navigator.platform || '';
  const isAndroid = /android/i.test(agent);
  const isLinux = /linux/i.test(platform) && !isAndroid;
  const isMac = /mac/i.test(platform);
  const chromium = !!window.chrome || /Chrome|Chromium|Edg|Brave|Vivaldi|OPR/.test(agent);

  function launcherHelp() {
    if (isLinux) {
      return '<h2>Keep this in your applications menu</h2>' +
        '<p>The launcher below has this phone\'s address in it. It opens a window of its own ' +
        'where it can, and your usual browser where it cannot.</p>' +
        '<p><a class="dl" href="/desktop-entry?token=' + encodeURIComponent(token) + '" ' +
        'download="messaging.desktop">Download launcher</a></p>' +
        '<p>Save it to <code>~/.local/share/applications/</code> and it appears with your ' +
        'other apps.</p>';
    }
    if (chromium) {
      const menu = isMac ? '⋮ menu → Cast, Save and Share' : '⋮ menu → Save and share';
      return '<h2>Keep this as an app</h2>' +
        '<p>Your browser can do this without anything to download: <b>' + menu + ' → ' +
        'Create shortcut…</b>, and tick <b>Open as window</b>. Edge calls it ' +
        '<b>Apps → Install this site as an app</b>.</p>' +
        '<p>You get a window with no tabs or address bar, and an entry in your ' +
        (isMac ? 'Dock and Launchpad' : 'Start menu and taskbar') + '.</p>';
    }
    if (isMac) {
      return '<h2>Keep this as an app</h2>' +
        '<p>In Safari: <b>File → Add to Dock</b>. It becomes a window of its own with an ' +
        'icon in the Dock.</p>';
    }
    return '<h2>Keep this to hand</h2>' +
      '<p>Bookmark this page — the address stays the same, token and all. Chrome, Edge and ' +
      'Brave can go further: <b>Create shortcut… → Open as window</b> gives it a window ' +
      'and a menu entry of its own.</p>';
  }

  if (launcherBtn && panel) {
    launcherBtn.addEventListener('click', () => {
      if (!panel.hidden) { panel.hidden = true; return; }
      panel.innerHTML = launcherHelp() + '<button type="button" class="close">Close</button>';
      panel.hidden = false;
      panel.querySelector('.close').addEventListener('click', () => { panel.hidden = true; });
    });
    document.addEventListener('click', e => {
      if (panel.hidden) return;
      if (!panel.contains(e.target) && e.target !== launcherBtn) panel.hidden = true;
    });
  }
})();
const threadsEl = document.getElementById('threads');
const messagesEl = document.getElementById('messages');
const paneTitleEl = document.getElementById('paneTitle');
const statusEl = document.getElementById('status');
const crossBtnEl = document.getElementById('crossBtn');
let crossTarget = null;
const findBtnEl = document.getElementById('findBtn');
const findBarEl = document.getElementById('findBar');
const findFieldEl = document.getElementById('findField');
const findCountEl = document.getElementById('findCount');
const findCloseEl = document.getElementById('findClose');
const bodyEl = document.getElementById('body');
const composerEl = document.getElementById('composer');
const sendEl = document.getElementById('send');
const newBtnEl = document.getElementById('newBtn');
const toWrapEl = document.getElementById('toWrap');
const toFieldEl = document.getElementById('toField');
const suggestionsEl = document.getElementById('suggestions');
const simFieldEl = document.getElementById('simField');
const signalSetupEl = document.getElementById('signalSetup');
const signalPayloadEl = document.getElementById('signalPayload');
const signalPairBtnEl = document.getElementById('signalPairBtn');
const signalSetupErrorEl = document.getElementById('signalSetupError');
const searchFieldEl = document.getElementById('searchField');
const searchClearEl = document.getElementById('searchClear');
const attachEl = document.getElementById('attach');
const fileFieldEl = document.getElementById('fileField');
const attachmentsEl = document.getElementById('attachments');
const emojiBtnEl = document.getElementById('emojiBtn');
const emojiPanelEl = document.getElementById('emojiPanel');
const emojiTabsEl = document.getElementById('emojiTabs');
const emojiGridEl = document.getElementById('emojiGrid');

let activeThreadId = null;
let activeThreadRail = 'sms'; // the open thread's rail; a SIM means nothing on Signal
let activeThreadTitle = '';
let composeMode = false;
// The number actually sent to. Set when a suggestion is picked, so the visible
// field can show a friendly name while we still send to the real address.
let chosenAddress = null;
let suggestions = [];
// The SIMs that can send. Empty on a one-SIM phone, which is most of them, and then
// nothing about the composer changes.
let sims = [];
let selIndex = -1;
let lookupTimer = null;
let lastThreads = [];
let lastMessagesSig = '';
let lastThreadsSig = '';
let filterQuery = '';
// Results from /api/search, which searches message bodies. Null means we are not searching
// and the list is the inbox. Filtering the fetched list, which is all this used to do, only
// ever matched a title or the single snippet a row carries.
let searchResults = null;
let searchSeq = 0;
// How many of the active thread's messages we're currently showing. Grows when the
// reader asks for older history.
let messageLimit = 300;
let hasMoreMessages = false;

function clearSuggestions() {
  suggestions = [];
  selIndex = -1;
  suggestionsEl.innerHTML = '';
}

function renderSuggestions() {
  suggestionsEl.innerHTML = '';
  suggestions.forEach((s, i) => {
    const row = document.createElement('div');
    row.className = 'suggestion' + (i === selIndex ? ' sel' : '');
    const n = document.createElement('div');
    n.className = 'sName';
    n.textContent = s.name || s.address;
    const a = document.createElement('div');
    a.className = 'sNum';
    a.textContent = s.address;
    row.append(n, a);
    row.addEventListener('mousedown', e => {
      e.preventDefault(); // keep focus so the composer stays usable
      pickSuggestion(i);
    });
    suggestionsEl.append(row);
  });
}

async function pickSuggestion(i) {
  const s = suggestions[i];
  if (!s) return;
  chosenAddress = s.address;
  toFieldEl.value = s.name ? s.name + ' <' + s.address + '>' : s.address;
  clearSuggestions();

  // If we've already got a conversation with this person, open it (with history)
  // rather than leaving them staring at an empty "New conversation" pane.
  try {
    const res = await api('/api/thread-for?address=' + encodeURIComponent(s.address));
    if (res.ok) {
      const info = await res.json();
      if (info.found && info.threadId) {
        await selectThread(info.threadId, info.title || s.name);
        bodyEl.focus();
        return;
      }
    }
  } catch (e) {
    // Not fatal — fall through and let them compose a new thread
    console.warn('thread lookup failed', e);
  }
  bodyEl.focus();
}

async function lookupContacts(q) {
  const res = await api('/api/contacts?q=' + encodeURIComponent(q));
  if (!res.ok) return;
  suggestions = await res.json().catch(() => []);
  selIndex = -1;
  renderSuggestions();
}

toFieldEl.addEventListener('input', () => {
  // Typing invalidates any previously picked contact
  chosenAddress = null;
  const q = toFieldEl.value.trim();
  clearTimeout(lookupTimer);
  if (q.length < 2) {
    clearSuggestions();
    return;
  }
  lookupTimer = setTimeout(() => lookupContacts(q), 180);
});

toFieldEl.addEventListener('keydown', e => {
  if (!suggestions.length) return;
  if (e.key === 'ArrowDown') {
    e.preventDefault();
    selIndex = (selIndex + 1) % suggestions.length;
    renderSuggestions();
  } else if (e.key === 'ArrowUp') {
    e.preventDefault();
    selIndex = (selIndex - 1 + suggestions.length) % suggestions.length;
    renderSuggestions();
  } else if (e.key === 'Enter') {
    if (selIndex >= 0) {
      e.preventDefault();
      pickSuggestion(selIndex);
    }
  } else if (e.key === 'Escape') {
    clearSuggestions();
  }
});

/** What to actually send to: the picked contact's number, else whatever was typed. */
function resolveRecipient() {
  if (chosenAddress) return chosenAddress;
  const raw = toFieldEl.value.trim();
  // Accept a pasted "Name <number>" form too
  const m = raw.match(/<([^>]+)>\s*$/);
  return m ? m[1].trim() : raw;
}

/* ── Per-conversation drafts ───────────────────────────────────────────────────
 * Unsent text belongs to the conversation it was typed in. Switching threads
 * stashes it against the thread being left and opens the new one with its own
 * draft (usually nothing, so a fresh box); coming back restores it.
 *
 * Held in memory only, by design — NOT localStorage. Closing or reloading the tab
 * discards drafts, which is the intended lifetime: this is message content, and it
 * has no business persisting in browser storage on a dashboard whose only gate is
 * a URL token. It also means no stale drafts pile up for threads long gone. */
const drafts = {};

/**
 * Save whatever is in the composer against the thread that's currently open.
 * Compose mode deliberately gets no draft slot: coming back to a "+" pane holding
 * old text with an empty recipient reads as a bug rather than a saved draft.
 */
function stashDraft() {
  if (composeMode || activeThreadId === null) return;
  const key = String(activeThreadId);
  const text = bodyEl.value;
  if (text.trim()) drafts[key] = text;
  else delete drafts[key]; // don't accumulate empties for every thread ever opened
  refreshDraftRow(activeThreadId);
}

function clearDraft(id) {
  delete drafts[String(id)];
  refreshDraftRow(id);
}

/**
 * Shared by the full render and the per-keystroke update, so both stay in step.
 * The italics alone mark a draft — no "Draft:" label; it just ate snippet width.
 */
function applySnippet(el, thread, draft) {
  el.className = draft ? 'snippet draft' : 'snippet';
  el.textContent = draft || (thread && thread.snippet) || '';
}

/**
 * Update one conversation's snippet in place. This runs on every keystroke, so it
 * deliberately does NOT call renderThreads() — that rebuilds all 300+ rows, which is
 * far too much work per character typed.
 */
function refreshDraftRow(id) {
  const row = threadsEl.querySelector('.thread[data-id="' + id + '"]');
  const snippet = row && row.querySelector('.snippet');
  if (!snippet) return; // filtered out of the list right now, or not rendered yet
  applySnippet(snippet, lastThreads.find(t => t.id === id), drafts[String(id)]);
}

/**
 * Size the composer to its content. The textarea starts at one row, so height has to
 * be reset before measuring or scrollHeight only ever ratchets upwards as text is
 * deleted. The CSS max-height caps it and takes over with a scrollbar past that.
 */
function autoGrow() {
  bodyEl.style.height = 'auto';
  bodyEl.style.height = bodyEl.scrollHeight + 'px';
}

// Keep the stashed draft current as they type, so switching away never loses a keystroke
bodyEl.addEventListener('input', () => {
  updateSendEnabled();
  stashDraft();
  autoGrow();
});

// Enter sends; Shift+Enter (and the other modifiers) fall through to the textarea's
// own newline. isComposing guards IME candidate selection, where Enter commits a word
// rather than ending the message.
bodyEl.addEventListener('keydown', e => {
  if (e.key !== 'Enter' || e.isComposing || e.keyCode === 229) return;
  if (e.shiftKey || e.altKey || e.ctrlKey || e.metaKey) return;
  e.preventDefault();
  if (!sendEl.disabled) composerEl.requestSubmit();
});

/*
 * Which SIM a new conversation goes out on.
 *
 * A reply never asks: it goes out on the SIM the conversation is already happening on, which
 * the phone works out for itself. It is only the first message of a new thread that has no
 * history to inherit from, and on a phone with two SIMs that is a real choice — without this
 * the browser could only ever start conversations on the default one.
 *
 * The phone sends an empty list when there is nothing to choose between, so this quietly does
 * nothing on the ordinary single-SIM phone.
 */
async function loadSims() {
  const data = await api('/api/sims').then(r => r.ok ? r.json() : null).catch(() => null);
  sims = (data && data.sims) || [];
  if (sims.length < 2) return;

  simFieldEl.innerHTML = '';
  sims.forEach(sim => {
    const option = document.createElement('option');
    option.value = String(sim.subId);
    option.textContent = simLabel(sim.subId);
    simFieldEl.append(option);
  });
}

/*
 * What to call a SIM.
 *
 * The carrier's own name for it, and the slot to tell two of the same carrier apart. Some
 * phones report no name at all, and then the slot is the whole label. One function so the
 * picker at the top and the marks down the thread always say the same words about the same
 * card; null for a subscription the phone no longer has, which is a SIM that has been taken
 * out since the message was sent and has no honest name left.
 */
function simLabel(subId) {
  const sim = sims.find(s => String(s.subId) === String(subId));
  if (!sim) return null;
  return sim.name ? sim.name + ' (SIM ' + sim.slot + ')' : 'SIM ' + sim.slot;
}

function enterComposeMode() {
  stashDraft(); // must run before composeMode/activeThreadId change out from under it
  composeMode = true;
  activeThreadId = null;
  activeThreadTitle = '';
  paneTitleEl.textContent = 'To:';
  toWrapEl.hidden = false;
  // Only when there is genuinely a choice; see loadSims.
  simFieldEl.hidden = sims.length < 2;
  // A new conversation always starts on SMS; there is no Signal compose path yet. Without
  // this the rail of the last thread opened would linger and gate the composer.
  activeThreadRail = 'sms';
  toFieldEl.value = '';
  chosenAddress = null;
  clearSuggestions();
  messagesEl.innerHTML = '<div id="empty">New conversation</div>';
  bodyEl.disabled = false;
  bodyEl.value = '';
  clearAttachments();
  autoGrow();
  toFieldEl.focus();
  // Drop the highlight from whatever thread was selected
  Array.from(threadsEl.children).forEach(el => el.classList.remove('active'));
}

function exitComposeMode() {
  composeMode = false;
  toWrapEl.hidden = true;
  simFieldEl.hidden = true;
  toFieldEl.value = '';
  chosenAddress = null;
  clearSuggestions();
}

newBtnEl.addEventListener('click', enterComposeMode);

// The clear button only exists while there is something to clear, so an empty box stays as
// plain as it was.
function syncSearchClearButton() {
  const hasQuery = searchFieldEl.value.length > 0;
  searchClearEl.hidden = !hasQuery;
  searchFieldEl.classList.toggle('has-query', hasQuery);
}

// Escape and the button do the same thing, through the same function - two copies of "clear
// the search" would eventually stop agreeing about what clearing means.
function clearSearch() {
  searchFieldEl.value = '';
  filterQuery = '';
  searchResults = null;
  searchSeq++;            // abandon anything still in flight
  syncSearchClearButton();
  renderThreads();
}

/**
 * Ask the phone to search, debounced. Two characters, matching the phone: one letter
 * matches most of an inbox and costs a full scan to say so.
 *
 * Every request carries a sequence number and a slower earlier one is discarded on arrival,
 * so typing quickly cannot leave the results of a prefix on screen.
 */
let searchTimer = null;
let searchAbort = null;
function runSearch() {
  const query = filterQuery.trim();
  // An earlier search that is still running is now worthless -- the reader has typed
  // past it. Dropping its result on arrival was not enough: each one scans every
  // conversation and copies the matches out of the database, so a fast typist could
  // leave half a dozen full scans running at once on a phone with one small CPU.
  if (searchAbort) searchAbort.abort();
  searchAbort = new AbortController();
  if (query.length < 2) {
    searchResults = null;
    renderThreads();
    return;
  }
  const seq = ++searchSeq;
  api('/api/search?q=' + encodeURIComponent(query), { signal: searchAbort.signal })
    .then(r => r.json())
    .then(d => {
      if (seq !== searchSeq) return;
      searchResults = d.results || [];
      renderThreads();
    })
    .catch(() => { /* leave the list alone; the status line already says if we are offline */ });
}

searchFieldEl.addEventListener('input', () => {
  filterQuery = searchFieldEl.value;
  syncSearchClearButton();
  // Redraw at once off what is already here, so typing feels immediate, and ask the phone
  // for the real answer a beat later.
  renderThreads();
  clearTimeout(searchTimer);
  searchTimer = setTimeout(runSearch, 200);
});

searchFieldEl.addEventListener('keydown', e => {
  if (e.key === 'Escape') {
    clearSearch();
  }
});

searchClearEl.addEventListener('click', () => {
  clearSearch();
  // Back to the box, so you can type a new search without reaching for the mouse again.
  searchFieldEl.focus();
});

/*
 * Pictures queued for the next send. They live here rather than in the file input because
 * the input can only hold what one pick put in it -- picking twice, or dropping a file onto
 * a thread that already has one queued, would silently replace the first.
 */
let pending = [];

/* A single MMS is small: the phone scales pictures down to the carrier's limit, but there is
 * no sense pushing a 40MB video over the tailnet only for the send to refuse it. */
const MAX_ATTACHMENT_BYTES = 25 * 1024 * 1024;

function addFiles(files) {
  let rejected = 0;
  Array.from(files || []).forEach(file => {
    if (file.size > MAX_ATTACHMENT_BYTES) { rejected++; return; }
    pending.push(file);
  });
  if (rejected) statusEl.textContent = rejected + ' file(s) too large';
  else clearSendFailure();
  renderAttachments();
}

function clearAttachments() {
  // Revoke first: each thumbnail holds an object URL, and dropping the list without
  // releasing them leaks the file's bytes for as long as the page stays open.
  Array.from(attachmentsEl.querySelectorAll('img')).forEach(img => URL.revokeObjectURL(img.src));
  pending = [];
  fileFieldEl.value = '';
  renderAttachments();
}

function renderAttachments() {
  Array.from(attachmentsEl.querySelectorAll('img')).forEach(img => URL.revokeObjectURL(img.src));
  attachmentsEl.innerHTML = '';
  attachmentsEl.hidden = pending.length === 0;

  pending.forEach((file, index) => {
    const thumb = document.createElement('div');
    thumb.className = 'thumb';

    if (file.type.startsWith('image/')) {
      const img = document.createElement('img');
      img.src = URL.createObjectURL(file);
      img.alt = file.name || 'attachment';
      thumb.appendChild(img);
    } else {
      const name = document.createElement('div');
      name.className = 'name';
      name.textContent = file.name || 'file';
      thumb.appendChild(name);
    }

    const remove = document.createElement('button');
    remove.type = 'button';
    remove.textContent = '\u00d7';
    remove.title = 'Remove';
    remove.addEventListener('click', () => {
      pending.splice(index, 1);
      renderAttachments();
    });
    thumb.appendChild(remove);

    attachmentsEl.appendChild(thumb);
  });

  updateSendEnabled();
}

/* A picture on its own is a message, so Send has to come alive for an empty box too. */
function updateSendEnabled() {
  const canSend = !bodyEl.disabled && (bodyEl.value.trim() !== '' || pending.length > 0);
  sendEl.disabled = !canSend;
  attachEl.disabled = bodyEl.disabled;
  emojiBtnEl.disabled = bodyEl.disabled;
}

attachEl.addEventListener('click', () => fileFieldEl.click());
fileFieldEl.addEventListener('change', () => addFiles(fileFieldEl.files));

/* Paste a screenshot straight into the composer -- the fastest path from a screen grab to a
 * text, and the one case where a filename is often missing entirely. */
bodyEl.addEventListener('paste', e => {
  const files = Array.from(e.clipboardData ? e.clipboardData.files : []);
  if (files.length) {
    e.preventDefault();
    addFiles(files);
  }
});

/* Drag and drop over the conversation pane. dragover must be cancelled or the browser
 * navigates to the file instead of handing it over. */
['dragenter', 'dragover'].forEach(name => {
  messagesEl.addEventListener(name, e => {
    if (bodyEl.disabled) return;
    e.preventDefault();
    messagesEl.classList.add('dropping');
  });
});
['dragleave', 'drop'].forEach(name => {
  messagesEl.addEventListener(name, () => messagesEl.classList.remove('dropping'));
});
messagesEl.addEventListener('drop', e => {
  if (bodyEl.disabled) return;
  e.preventDefault();
  addFiles(e.dataTransfer && e.dataTransfer.files);
});

/*
 * Build the request for a send. With nothing attached this stays the JSON the relay has
 * always taken; with a file it becomes multipart, because base64 in a JSON body would inflate
 * the bytes by a third for no gain. Field names match what the relay looks for: attachment0,
 * attachment1, and so on.
 */
/*
 * Say what actually went wrong. The relay refuses a send whose picture it cannot read, and
 * "send failed" on its own would leave someone retrying the same unreadable file forever.
 * The composer keeps its text and its queue either way, so a retry is one click.
 */
async function reportSendFailure(res) {
  const detail = await res.json().then(j => j && j.error).catch(() => null);
  statusEl.textContent = detail || 'send failed';
}

/*
 * Put the status line back to what the connection is actually doing. Without this a refusal
 * stays on screen after the next send succeeds -- and "could not be read as a picture" sitting
 * above a message that plainly went is worse than no message at all.
 */
function clearSendFailure() {
  statusEl.textContent = statusEl.classList.contains('live') ? 'live' : 'reconnecting…';
}

function sendRequestBody(fields) {
  if (pending.length === 0) {
    return {
      // The charset is not decoration. NanoHTTPD decodes a request body with the charset
      // named in this header and falls back to US-ASCII when there is none -- which turns
      // every emoji, accent and curly quote into replacement characters, unrecoverably,
      // before the message is ever sent.
      headers: { 'Content-Type': 'application/json; charset=utf-8' },
      body: JSON.stringify(fields)
    };
  }

  const form = new FormData();
  // Multipart cannot be fixed the same way: the browser writes the Content-Type itself
  // (it has to -- the boundary is its own), so the request carries no charset and the text
  // fields would be read as US-ASCII. Base64 is pure ASCII and survives that untouched.
  Object.keys(fields).forEach(key => form.append(key + 'B64', toBase64Utf8(fields[key])));
  pending.forEach((file, index) => {
    form.append('attachment' + index, file, file.name || ('attachment' + index));
  });
  // No Content-Type header, so the browser sets it along with the boundary it generated.
  return { body: form };
}

/*
 * The emoji picker. The catalogue is Unicode's own, already narrowed at build time to the
 * codepoints the Kompakt's font can actually draw as a single glyph -- so nothing offered here
 * can arrive on the phone as an empty box. Skin tones and the family sequences are left out for
 * the same reason: this font has no ligatures for them, and they would come apart into pieces.
 */
let emojiLoaded = false;

async function loadEmoji() {
  if (emojiLoaded) return;
  const categories = await api('/emoji.json').then(r => r.json()).catch(() => null);
  if (!categories) return;
  emojiLoaded = true;

  categories.forEach((category, index) => {
    const tab = document.createElement('button');
    tab.type = 'button';
    tab.textContent = category.name;
    tab.addEventListener('click', () => {
      Array.from(emojiTabsEl.children).forEach(b => b.classList.remove('on'));
      tab.classList.add('on');
      showEmoji(category.emoji);
    });
    emojiTabsEl.appendChild(tab);
    if (index === 0) { tab.classList.add('on'); showEmoji(category.emoji); }
  });
}

function showEmoji(list) {
  emojiGridEl.innerHTML = '';
  const frag = document.createDocumentFragment();
  list.forEach(glyph => {
    const b = document.createElement('button');
    b.type = 'button';
    b.textContent = glyph;
    b.addEventListener('click', () => insertAtCursor(glyph));
    frag.appendChild(b);
  });
  emojiGridEl.appendChild(frag);
  emojiGridEl.scrollTop = 0;
}

/* Insert where the caret is, not at the end -- an emoji usually belongs mid-sentence, and
 * appending would make the picker useless for anything but the last character. */
function insertAtCursor(text) {
  const start = bodyEl.selectionStart, end = bodyEl.selectionEnd;
  bodyEl.value = bodyEl.value.slice(0, start) + text + bodyEl.value.slice(end);
  const caret = start + text.length;
  bodyEl.setSelectionRange(caret, caret);
  bodyEl.focus();
  autoGrow();
  updateSendEnabled();
  stashDraft();
}

function toggleEmojiPanel(open) {
  const show = open === undefined ? !emojiPanelEl.classList.contains('open') : open;
  emojiPanelEl.classList.toggle('open', show);
  if (show) loadEmoji();
}

emojiBtnEl.addEventListener('click', () => toggleEmojiPanel());
document.addEventListener('keydown', e => {
  if (e.key === 'Escape' && emojiPanelEl.classList.contains('open')) toggleEmojiPanel(false);
});

/** UTF-8 bytes of a string, base64'd. btoa alone throws on anything above U+00FF. */
function toBase64Utf8(text) {
  const bytes = new TextEncoder().encode(text == null ? '' : String(text));
  let binary = '';
  bytes.forEach(b => { binary += String.fromCharCode(b); });
  return btoa(binary);
}

function api(path, options) {
  const opts = options || {};
  opts.headers = Object.assign({ 'Authorization': 'Bearer ' + token }, opts.headers || {});
  return fetch(path, opts).then(res => {
    // A stored token stops working when the phone mints a new one -- "Reset Desktop Sync
    // link" is there precisely so it can. Without this the browser holds a token that will
    // never be accepted again and every request 401s for ever, with no prompt, because the
    // code gate only appears when there is NO token at all.
    if (res.status === 401 && token) {
      try { localStorage.removeItem(STORED_TOKEN_KEY); } catch { /* nothing to clear */ }
      token = '';
      showPairCodePrompt('This link is no longer valid — the phone has issued a new one. ' +
        'Open Settings &rarr; Desktop Sync &rarr; Show link and enter the code.');
    }
    return res;
  });
}

/**
 * How long a disappearing message has left, in the coarsest unit that is still true.
 * "in 3 days" is more use than a count of hours, and a message already past its deadline
 * is shown as going rather than as gone -- the sweep that removes it runs on its own
 * schedule, and claiming it is gone while it is on screen would be the wrong kind of true.
 */
function expiryLabel(at) {
  const left = at - Date.now();
  if (left <= 0) return 'expiring';
  const mins = Math.round(left / 60000);
  if (mins < 60) return 'in ' + Math.max(1, mins) + 'm';
  const hours = Math.round(mins / 60);
  if (hours < 48) return 'in ' + hours + 'h';
  return 'in ' + Math.round(hours / 24) + 'd';
}

function formatTime(ms) {
  if (!ms) return '';
  const d = new Date(ms);
  const now = new Date();
  const sameDay = d.toDateString() === now.toDateString();
  if (sameDay) {
    return d.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
  }
  return d.toLocaleDateString([], { month: 'short', day: 'numeric' });
}

// Which shelf the list is showing. The archive is a place things go into from the row
// menu, so there has to be a way to look at it -- otherwise archiving in the browser means
// the conversation leaves and can only be found again on the phone.
let showingArchived = false;

async function loadThreads() {
  const res = await api('/api/threads' + (showingArchived ? '?archived=1' : ''));
  if (!res.ok) {
    statusEl.textContent = res.status === 401 ? 'bad token' : 'error';
    statusEl.classList.remove('live');
    return;
  }
  const threads = await res.json();
  lastThreads = threads;

  // Same reasoning as loadMessages: don't rebuild the list (and lose its scroll
  // position) on every poll tick when nothing changed. The filter text is part of
  // the signature so typing in the search box re-renders immediately.
  const sig = threads.map(t => t.id + ':' + t.date + ':' + (t.unread ? 'u' : 'r')).join('|') +
    '#' + activeThreadId + '#' + filterQuery + '#' + showingArchived;
  if (sig === lastThreadsSig) return;
  lastThreadsSig = sig;
  renderThreads();
}

/** Draw the (optionally filtered) conversation list from whatever we last fetched. */
function renderThreads() {
  const prevScroll = threadsEl.scrollTop;
  const q = filterQuery.trim().toLowerCase();
  // Server results when we have them, otherwise the local filter -- which is what shows
  // between a keystroke and the phone answering, and when the query is too short to send.
  const threads = !q ? lastThreads
    : (searchResults !== null ? searchResults : lastThreads.filter(t =>
        (t.title || '').toLowerCase().includes(q) || (t.snippet || '').toLowerCase().includes(q)));

  // Shown only when it has something to do, the same rule the phone applies to the same
  // item -- and judged on the whole inbox, not the filtered view, or searching would hide
  // a button whose job is the inbox.
  markAllBtn.hidden = showingArchived || !lastThreads.some(t => t.unread);

  threadsEl.innerHTML = '';
  if (!threads.length) {
    const none = document.createElement('div');
    none.id = 'noMatches';
    none.textContent = q ? 'No conversations match “' + filterQuery.trim() + '”' : 'No conversations yet';
    threadsEl.append(none);
    return;
  }
  threads.forEach(t => {
    const div = document.createElement('div');
    div.className = 'thread' + (t.unread ? ' unread' : '') + (t.id === activeThreadId ? ' active' : '');
    const row = document.createElement('div');
    row.className = 'row';
    const name = document.createElement('span');
    name.className = 'name';
    name.textContent = t.title || '(no name)';
    const when = document.createElement('span');
    when.className = 'when';
    when.textContent = formatTime(t.date);
    row.append(name);
    // Which rail the thread rides. Only Signal is marked: unmarked means SMS, which keeps
    // the badge off nearly every row in a list that is mostly SMS.
    if (t.rail === 'signal') {
      const rail = document.createElement('span');
      rail.className = 'rail';
      rail.textContent = 'Signal';
      row.append(rail);
    }
    row.append(when);
    const snippet = document.createElement('div');
    if (t.matches > 0) {
      // A hit inside a conversation, not the conversation's last line.
      snippet.className = 'snippet';
      snippet.textContent = t.matches + (t.matches === 1 ? ' message' : ' messages');
    } else {
      applySnippet(snippet, t, drafts[String(t.id)]);
    }
    div.dataset.id = t.id; // lets a keystroke update just this row, see refreshDraftRow
    div.append(row, snippet);
    div.addEventListener('click', () => selectThread(t.id, t.title));
    // The phone opens this set with a long press; on a keyboard and mouse it is the
    // right-click, and on a touchscreen the long press still arrives as one.
    div.addEventListener('contextmenu', e => {
      e.preventDefault();
      openThreadMenu(t, e.clientX, e.clientY);
    });
    threadsEl.append(div);
  });
  threadsEl.scrollTop = prevScroll;
}

const threadMenuEl = document.getElementById('threadMenu');
const markAllBtn = document.getElementById('markAllBtn');

/**
 * Everything the phone offers on a conversation. The rails do not offer the same set --
 * Signal has mute and no delete here, SMS has delete and no mute -- so the menu is built
 * per row from what that rail can actually do, rather than showing an item that answers
 * with an error.
 */
function openThreadMenu(t, x, y) {
  const signal = t.rail === 'signal';
  const items = [];
  // Only Signal has safety numbers; SMS has no such idea, so the item is not offered
  // there rather than offered and refused.
  if (signal) items.push(['Safety number\u2026', '@info']);
  items.push(
    ['Mark unread', 'unread'],
    [t.pinned ? 'Unpin' : 'Pin', t.pinned ? 'unpin' : 'pin'],
  );
  if (signal) items.push([t.muted ? 'Unmute' : 'Mute', t.muted ? 'unmute' : 'mute']);
  items.push([t.archived ? 'Move to inbox' : 'Archive', t.archived ? 'unarchive' : 'archive']);
  items.push(null);
  // SMS keeps a blocked flag on the row, so it can be a toggle. Signal's block lives on
  // the account rather than here, and the row cannot say which way round it is, so that
  // rail is offered the action and not a claim about its current state.
  if (signal) items.push(['Block', 'block', true]);
  else items.push([t.blocked ? 'Unblock' : 'Block', t.blocked ? 'unblock' : 'block', true]);
  // Deleting a Signal conversation here would clear only this device's copy while the
  // bridge kept its own, which reads as "it came back" the next time anything syncs. Not
  // offered rather than offered and surprising.
  if (!signal) items.push(['Delete', 'delete', true]);

  threadMenuEl.innerHTML = '';
  items.forEach(item => {
    if (!item) { threadMenuEl.append(document.createElement('hr')); return; }
    const [label, action, danger] = item;
    const b = document.createElement('button');
    b.type = 'button';
    b.textContent = label;
    if (danger) b.className = 'danger';
    b.addEventListener('click', () => runThreadAction(t, action, label));
    threadMenuEl.append(b);
  });

  threadMenuEl.hidden = false;
  // Placed after unhiding, so the measurement is of something with a size. Kept on screen
  // at the bottom and right edges, where a menu opened on the last row would otherwise
  // hang off the window.
  const r = threadMenuEl.getBoundingClientRect();
  const left = Math.min(x, window.innerWidth - r.width - 8);
  const top = Math.min(y, window.innerHeight - r.height - 8);
  threadMenuEl.style.left = Math.max(8, left) + 'px';
  threadMenuEl.style.top = Math.max(8, top) + 'px';
}

function closeThreadMenu() { threadMenuEl.hidden = true; }
document.addEventListener('click', e => {
  if (!threadMenuEl.hidden && !threadMenuEl.contains(e.target)) closeThreadMenu();
});
document.addEventListener('keydown', e => { if (e.key === 'Escape') closeThreadMenu(); });
window.addEventListener('resize', closeThreadMenu);

async function runThreadAction(t, action, label) {
  closeThreadMenu();
  // Not a state change, so it does not go down the action route.
  if (action === '@info') return showSafetyNumber(t);
  // The two that cannot be taken back ask first. Everything else is a toggle the same
  // menu will undo.
  if (action === 'delete' && !confirm('Delete this conversation? This cannot be undone.')) return;
  if (action === 'block' && !confirm('Block ' + (t.title || 'this sender') + '?')) return;
  try {
    const res = await api('/api/threads/' + t.id + '/action', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json; charset=utf-8' },
      body: JSON.stringify({ action })
    });
    if (!res.ok) {
      const d = await res.json().catch(() => ({}));
      statusEl.textContent = d.error || (label + ' did not work');
      return;
    }
    // The open thread has just been deleted or filed away; showing it still open invites
    // a reply into a conversation that is no longer there.
    if ((action === 'delete' || action === 'archive') && activeThreadId === t.id) {
      activeThreadId = null;
      lastMessagesSig = '';
      paneTitleEl.textContent = 'Select a conversation';
      messagesEl.innerHTML = '';
      bodyEl.disabled = true;
      crossBtnEl.hidden = true;
      findBtnEl.hidden = true;
      findBarEl.hidden = true;
    }
    lastThreadsSig = '';
    await loadThreads();
  } catch (e) {
    statusEl.textContent = 'the phone did not answer';
  }
}

const archiveBtn = document.getElementById('archiveBtn');

archiveBtn.addEventListener('click', async () => {
  showingArchived = !showingArchived;
  archiveBtn.textContent = showingArchived ? '← Inbox' : 'Archived';
  // The open conversation may not be on the shelf being shown; leaving it open would put
  // a composer under a thread the list no longer contains.
  activeThreadId = null;
  lastMessagesSig = '';
  lastThreadsSig = '';
  messagesEl.innerHTML = '';
  paneTitleEl.textContent = 'Select a conversation';
  bodyEl.disabled = true;
  crossBtnEl.hidden = true;
  findBtnEl.hidden = true;
  findBarEl.hidden = true;
  threadQuery = '';
  await loadThreads();
});

/**
 * The safety number for a one-to-one Signal thread, and whether the key behind it is still
 * the accepted one.
 *
 * A changed safety number is the one thing in a messenger worth interrupting someone for:
 * the key at the other end is not the key you last talked to, which is a reinstall or
 * somebody in the middle. Asked of the bridge each time rather than remembered, because a
 * stale safety number is worse than none -- it reassures.
 */
async function showSafetyNumber(t) {
  let d;
  try {
    const res = await api('/api/threads/' + t.id + '/info');
    d = await res.json();
  } catch (e) {
    statusEl.textContent = 'the phone did not answer';
    return;
  }
  const panel = document.getElementById('infoPanel');
  const body = document.getElementById('infoBody');
  body.innerHTML = '';

  const h = document.createElement('h2');
  h.textContent = d.error ? 'Safety number' : 'Safety number with ' + (d.title || 'this contact');
  body.append(h);

  if (d.error || d.pending) {
    const p = document.createElement('p');
    p.textContent = d.error ||
      'No safety number yet — one exists once you have exchanged a message with them ' +
      'on Signal from this account.';
    body.append(p);
  } else {
    // Monospaced and grouped in fives, the way Signal prints it, because the only thing
    // anyone does with a safety number is read it aloud to compare.
    const num = document.createElement('div');
    num.className = 'safetyNumber';
    num.textContent = d.safetyNumber;
    body.append(num);

    const state = document.createElement('p');
    if (d.changed) {
      state.className = 'changed';
      state.textContent = 'This has CHANGED since you last spoke. That is a reinstall, a ' +
        'new device, or someone in the middle. Check it with them over something other ' +
        'than Signal before trusting it.';
    } else {
      state.textContent = d.verified ? 'Marked verified.' : 'Not verified yet.';
    }
    body.append(state);
  }
  panel.hidden = false;
}

document.getElementById('infoClose').addEventListener('click', () => {
  document.getElementById('infoPanel').hidden = true;
});
document.addEventListener('keydown', e => {
  if (e.key === 'Escape') document.getElementById('infoPanel').hidden = true;
});

markAllBtn.addEventListener('click', async () => {
  markAllBtn.disabled = true;
  try {
    const res = await api('/api/mark-all-read', { method: 'POST' });
    if (!res.ok) statusEl.textContent = 'mark all read did not work';
    lastThreadsSig = '';
    await loadThreads();
  } catch (e) {
    statusEl.textContent = 'the phone did not answer';
  } finally {
    markAllBtn.disabled = false;
  }
});

async function selectThread(id, title) {
  stashDraft(); // capture unsent text for the thread we're leaving, before it changes
  exitComposeMode();
  lastMessagesSig = ''; // force a fresh render for the newly opened thread
  messageLimit = 300;    // start each thread at the most recent page
  hasMoreMessages = false;
  activeThreadId = id;
  // A find belongs to the conversation it was made in; carrying it into the next one
  // would show someone else's thread filtered by a word they never said.
  threadQuery = '';
  findBarEl.hidden = true;
  findFieldEl.value = '';
  findCountEl.textContent = '';
  findBtnEl.hidden = false;
  activeThreadRail = (lastThreads.find(t => t.id === id) || {}).rail || 'sms';
  activeThreadTitle = title || '';
  paneTitleEl.textContent = activeThreadTitle || 'Conversation';
  bodyEl.disabled = false;
  // Restore before the awaits below so the box is right immediately, not a beat later
  bodyEl.value = drafts[String(id)] || '';
  clearAttachments();
  autoGrow();
  await loadMessages();
  // Reading it here should clear the unread dot and notification on the phone too
  await markThreadRead(id);
  await loadThreads();
  loadCrossRail(id);
}

/**
 * The phone's rail badge, in the browser: if the person in this thread also has a
 * conversation on the other rail, offer a way across to it.
 *
 * Asked once when a thread is opened rather than sent with the list. The list is hundreds
 * of rows and polls every few seconds, and a directory lookup per row per tick is a lot of
 * work to answer a question about the one thread being read. Not awaited by selectThread
 * either -- the badge appearing a moment late is better than the messages arriving late.
 */
async function loadCrossRail(id) {
  crossBtnEl.hidden = true;
  crossTarget = null;
  try {
    const res = await api('/api/threads/' + id + '/cross');
    if (!res.ok) return;
    const d = await res.json();
    // The thread may have been changed while this was in flight.
    if (!d.found || activeThreadId !== id) return;
    crossTarget = d;
    crossBtnEl.textContent = d.label + ' \u203a';
    crossBtnEl.hidden = false;
  } catch (e) { /* no badge is the right failure here */ }
}

crossBtnEl.addEventListener('click', () => {
  if (crossTarget) selectThread(crossTarget.id, crossTarget.title);
});

/** Finding inside the open conversation. Closing it puts the whole thread back. */
function openFind() {
  findBarEl.hidden = false;
  findFieldEl.focus();
  findFieldEl.select();
}

async function closeFind() {
  findBarEl.hidden = true;
  findFieldEl.value = '';
  findCountEl.textContent = '';
  if (threadQuery) {
    threadQuery = '';
    lastMessagesSig = '';
    await loadMessages();
  }
}

findBtnEl.addEventListener('click', () => {
  if (findBarEl.hidden) openFind(); else closeFind();
});
findCloseEl.addEventListener('click', closeFind);

let findTimer = null;
findFieldEl.addEventListener('input', () => {
  clearTimeout(findTimer);
  // Debounced, and two characters before anything is sent -- the same floor the
  // conversation search uses, for the same reason: one letter matches most of a thread.
  findTimer = setTimeout(async () => {
    const q = findFieldEl.value.trim();
    const next = q.length >= 2 ? q : '';
    if (next === threadQuery) return;
    threadQuery = next;
    lastMessagesSig = '';
    await loadMessages();
  }, 220);
});
findFieldEl.addEventListener('keydown', e => { if (e.key === 'Escape') closeFind(); });

/** Tell the phone this thread has been read (clears its notification + badge). */
async function markThreadRead(id) {
  if (id === null || id === undefined) return;
  try {
    await api('/api/threads/' + id + '/read', { method: 'POST' });
  } catch (e) {
    console.warn('mark read failed', e);
  }
}

// What is being looked for inside the open conversation, or ''. Sent to the phone rather
// than filtered here: the browser holds only the most recent page, so a find that only
// looked at what is loaded would quietly miss the older half of a long thread.
let threadQuery = '';

async function loadMessages() {
  if (activeThreadId === null) return;
  const res = await api('/api/threads/' + activeThreadId + '/messages?limit=' + messageLimit +
    (threadQuery ? '&q=' + encodeURIComponent(threadQuery) : ''));
  if (!res.ok) return;
  const payload = await res.json();
  // Response used to be a bare array; it's now {total, hasMore, messages}
  const messages = Array.isArray(payload) ? payload : (payload.messages || []);
  hasMoreMessages = Array.isArray(payload) ? false : !!payload.hasMore;
  if (threadQuery) {
    const n = messages.length;
    findCountEl.textContent = n === 0 ? 'no matches' : n + (n === 1 ? ' match' : ' matches');
  } else {
    findCountEl.textContent = '';
  }

  // The poll runs every few seconds. Rebuilding the DOM each time would throw away
  // the reader's scroll position (and any in-flight image loads), so bail out when
  // nothing has actually changed.
  const sig = activeThreadId + ':' + messageLimit + ':' + threadQuery + ':' + messages.length + ':' +
    (messages.length ? messages[messages.length - 1].id + ':' + messages[messages.length - 1].date : '');
  if (sig === lastMessagesSig) return;
  const isNewThread = !lastMessagesSig.startsWith(activeThreadId + ':');
  lastMessagesSig = sig;

  // Only auto-scroll if they're already reading the bottom (or just opened the
  // thread) — otherwise scrolling up to read history gets yanked back down.
  const nearBottom = messagesEl.scrollHeight - messagesEl.scrollTop - messagesEl.clientHeight < 120;

  messagesEl.innerHTML = '';

  // Let the reader reach history older than the current page
  if (hasMoreMessages) {
    const more = document.createElement('button');
    more.type = 'button';
    more.id = 'loadMore';
    more.textContent = 'Load earlier messages';
    more.addEventListener('click', async () => {
      more.disabled = true;
      more.textContent = 'Loading…';
      // Anchor on distance-from-bottom: prepending older messages changes
      // scrollHeight, so keeping scrollTop would visibly jump the view.
      const fromBottom = messagesEl.scrollHeight - messagesEl.scrollTop;
      messageLimit += 300;
      await loadMessages();
      messagesEl.scrollTop = messagesEl.scrollHeight - fromBottom;
    });
    messagesEl.append(more);
  }

  // Walks with the loop so each message can be compared with the one above it.
  let previousSubId = null;

  messages.forEach(m => {
    const wrap = document.createElement('div');
    wrap.className = 'msg' + (m.isMe ? ' me' : '');
    const inner = document.createElement('div');
    inner.className = 'stack';
    const bubble = document.createElement('div');
    bubble.className = 'bubble';
    const text = (m.body || '').trim();
    if (text) bubble.textContent = text;

    // A view-once photo leaves a row with no body and no attachment, deliberately: the
    // bridge keeps it so the conversation does not have a silent hole where a message was.
    // Drawn without a marker that is the hole anyway, and indistinguishable from something
    // failing to load.
    if (!text && m.viewOnce) {
      const note = document.createElement('div');
      note.className = 'viewOnce';
      note.textContent = '\u{1F441} View-once photo. Not kept.';
      bubble.append(note);
    }

    // Attachments, on either rail — without these a picture message is just an empty
    // bubble. Each row says where to fetch itself, because MMS parts and Signal
    // attachments live in different places and answer on different routes; the drawing
    // below does not need to know which is which.
    (m.attachments || []).forEach(att => {
        // Our own sent Signal attachments carry no id: Signal assigns one on upload and
        // never reports it back, so there is nothing to fetch. Say the message carried
        // something rather than drawing a broken picture.
        if (!att.url) {
          const note = document.createElement('div');
          note.className = 'attachLink';
          note.textContent = '📎 ' + (att.label || att.type || 'Attachment');
          bubble.append(note);
          return;
        }
        const src = att.url + '?token=' + encodeURIComponent(token);
        if (att.isImage) {
          const img = document.createElement('img');
          img.className = 'attach';
          // Deliberately NOT loading="lazy": the bubble starts at zero height, so the
          // lazy heuristic never fires and the image sits at complete=false forever.
          img.alt = att.label || 'Picture';
          // Token goes in the query string: <img src> can't send an auth header.
          img.src = src;
          // If it genuinely fails, fall back to a link rather than showing nothing
          img.addEventListener('error', () => {
            const a = document.createElement('a');
            a.className = 'attachLink';
            a.href = img.src;
            a.target = '_blank';
            a.rel = 'noopener';
            a.textContent = '📎 ' + (att.label || 'Picture');
            img.replaceWith(a);
          });
          bubble.append(img);
        } else if (att.isVideo) {
          // Played here rather than handed over as a link. The phone serves parts with
          // a length and answers range requests now, which is what a video element needs
          // before it will play anything at all.
          const video = document.createElement('video');
          video.className = 'attach';
          video.controls = true;
          video.preload = 'metadata';
          video.playsInline = true;
          video.src = src;
          // Some MMS video is in codecs no browser decodes. Say so and offer the file,
          // rather than leaving a black rectangle that never explains itself.
          video.addEventListener('error', () => {
            const wrap = document.createElement('div');
            wrap.className = 'attachLink';
            const a = document.createElement('a');
            a.href = video.src;
            a.target = '_blank';
            a.rel = 'noopener';
            a.textContent = '🎬 ' + (att.label || 'Video');
            wrap.append(a, document.createTextNode(' — this browser cannot play it; save it instead'));
            video.replaceWith(wrap);
          });
          bubble.append(video);
        } else {
          const link = document.createElement('a');
          link.className = 'attachLink';
          link.href = src;
          link.target = '_blank';
          link.rel = 'noopener';
          link.textContent = '📎 ' + (att.label || att.type);
          bubble.append(link);
        }
      });

    if (!text && !(m.attachments || []).length) bubble.textContent = '';

    // A message that did not go has to say so. The phone knows within a second or two —
    // a SIM it cannot send on, no radio — and until this was here the bubble looked
    // exactly like one that had gone, so the failure was only ever visible on the phone.
    // The dashed edge carries it without colour, which is the whole palette here anyway.
    if (m.status === 'failed') bubble.classList.add('failed');

    const stamp = document.createElement('div');
    stamp.className = 'stamp';
    // Which SIM carried it, on the phones where that is a question at all. Marked where
    // it changes and on the first message shown, which is the phone's own rule plus the
    // one case the phone does not have: paging back through history arrives mid-thread,
    // and a run of messages whose mark scrolled off above is a run with no answer on it.
    // A reader with a work number and a personal one is looking for the switch, so the
    // switch is what gets drawn rather than a label on every bubble.
    const sim = sims.length > 1 ? simLabel(m.subId) : null;
    const marksSim = sim !== null && (previousSubId === null || String(m.subId) !== String(previousSubId));
    previousSubId = m.subId;

    stamp.textContent = formatTime(m.date)
      + (m.status === 'failed' ? ' · not sent'
        : m.status === 'sending' ? ' · sending…'
        : '')
      + (marksSim ? ' · ' + sim : '')
      // A disappearing message says when it goes. Without it a thread that empties itself
      // is indistinguishable from one that lost something, and the reader has no way to
      // know a message they are looking at is on a clock.
      + (m.expiresAt ? ' · ' + expiryLabel(m.expiresAt) : '');
    // In a group the phone sends who each received message is from; one-to-one threads
    // send nothing, because the name is already at the top of the screen.
    if (m.from) {
      const who = document.createElement('div');
      who.className = 'who';
      who.textContent = m.from;
      inner.append(who, bubble, stamp);
    } else {
      inner.append(bubble, stamp);
    }
    wrap.append(inner);
    messagesEl.append(wrap);
  });
  if (isNewThread || nearBottom) {
    messagesEl.scrollTop = messagesEl.scrollHeight;
  }
}

/* ── Picture viewer ────────────────────────────────────────────────────────────
 * Click any MMS image to see it at full size. The handler is delegated off
 * #messages rather than bound per-<img>, so it keeps working across the poll's
 * DOM rebuilds and the "Load earlier messages" paging without any rebinding. */
const lightboxEl = document.getElementById('lightbox');
const lbImgEl = document.getElementById('lbImg');
const lbOpenEl = document.getElementById('lbOpen');

function openLightbox(src, alt) {
  lbImgEl.src = src;
  lbImgEl.alt = alt || '';
  lbOpenEl.href = src;
  lightboxEl.hidden = false;
}

function closeLightbox() {
  lightboxEl.hidden = true;
  // Drop the src so a large picture isn't kept decoded behind an invisible overlay
  lbImgEl.removeAttribute('src');
}

messagesEl.addEventListener('click', e => {
  const img = e.target.closest('img.attach');
  if (img) openLightbox(img.src, img.alt);
});

lightboxEl.addEventListener('click', e => {
  // Let the "open full size" link do its job; a click anywhere else dismisses.
  if (!e.target.closest('#lbOpen')) closeLightbox();
});

// Capture phase + stopPropagation so Escape closes the viewer instead of also
// reaching the search/recipient fields' own Escape handlers underneath it.
document.addEventListener('keydown', e => {
  if (e.key === 'Escape' && !lightboxEl.hidden) {
    e.stopPropagation();
    closeLightbox();
  }
}, true);

composerEl.addEventListener('submit', async e => {
  e.preventDefault();
  const text = bodyEl.value.trim();
  // A picture with no caption is a perfectly good message.
  if (!text && pending.length === 0) return;

  if (composeMode) {
    const to = resolveRecipient();
    if (!to) {
      toFieldEl.focus();
      return;
    }
    sendEl.disabled = true;
    // A string, so the JSON and multipart encodings carry it the same way. Left out
    // entirely when there is no choice to make, so a one-SIM phone sends what it always did.
    const fields = { to: to, body: text };
    if (activeThreadRail !== 'signal' && sims.length > 1 && simFieldEl.value) {
      fields.subId = simFieldEl.value;
    }
    const res = await api('/api/compose', Object.assign(
      { method: 'POST' },
      sendRequestBody(fields)
    ));
    updateSendEnabled();
    if (!res.ok) {
      await reportSendFailure(res);
      return;
    }
    const result = await res.json().catch(() => ({}));
    clearSendFailure();
    bodyEl.value = '';
    clearAttachments();
    autoGrow();
    exitComposeMode();
    await loadThreads();
    // Jump into the conversation that was just created, if the phone told us which.
    if (result.threadId) {
      await selectThread(result.threadId, to);
    } else {
      paneTitleEl.textContent = 'Sent';
    }
    return;
  }

  if (activeThreadId === null) return;
  // Pin the thread for the duration of the request: they can switch conversations
  // while it's in flight, and the draft that gets cleared must be the one that was
  // actually sent, not whatever is on screen when the response lands.
  const sentThreadId = activeThreadId;
  sendEl.disabled = true;
  const res = await api('/api/threads/' + sentThreadId + '/send', Object.assign(
    { method: 'POST' },
    sendRequestBody({ body: text })
  ));
  updateSendEnabled();
  if (res.ok) {
    clearSendFailure();
    clearDraft(sentThreadId);
    // Only blank the box if they're still looking at the thread they sent from
    if (activeThreadId === sentThreadId) bodyEl.value = '';
    // The queue is not per-thread, so it clears either way -- leaving a picture
    // attached after switching conversations is how you send it to the wrong person.
    clearAttachments();
    autoGrow();
    await loadMessages();
    await loadThreads();
  } else {
    await reportSendFailure(res);
  }
});

function connectSocket() {
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
  const ws = new WebSocket(proto + '//' + location.host + '/?token=' + encodeURIComponent(token));

  ws.addEventListener('open', () => {
    statusEl.textContent = 'live';
    statusEl.classList.add('live');
  });

  // MUST catch: loadThreads/loadMessages reject if the phone is briefly
  // unreachable, and an uncaught rejection inside an async listener is silent —
  // which made pushes look like they were being ignored entirely.
  ws.addEventListener('message', () => { refresh(); });

  ws.addEventListener('close', () => {
    statusEl.textContent = 'reconnecting…';
    statusEl.classList.remove('live');
    setTimeout(connectSocket, 3000);
  });

  ws.addEventListener('error', () => ws.close());
}

/** Pull the latest view. Safe to call any time; no-ops the message pane if nothing is open. */
async function refresh() {
  try {
    await loadThreads();
    await loadMessages();
    // If a new message landed in the thread we're already looking at, clear it on
    // the phone too — but only when there's actually something unread, so we're not
    // firing a write + notification update every poll tick.
    if (activeThreadId !== null) {
      const open = lastThreads.find(t => t.id === activeThreadId);
      if (open && open.unread) {
        await markThreadRead(activeThreadId);
        await loadThreads();
      }
    }
  } catch (e) {
    // Never let a transient fetch error kill the refresh loop
    console.warn('refresh failed', e);
  }
}

loadThreads();
loadSims();
connectSocket();

// Belt-and-braces polling alongside the WebSocket. The socket makes updates
// near-instant when it's healthy, but it can go quiet after laptop sleep, a
// network switch, or a dropped push — polling guarantees the view still catches
// up on its own without a manual reload.
setInterval(refresh, 5000);

/*
 * Offer to set Signal up, but only while it is not.
 *
 * Pairing means a link about 140 characters long, two thirds of it a hex certificate
 * fingerprint, and the alternative is typing it into an e-ink phone. Here it is a paste on
 * the same machine that printed it. Once paired this disappears and does not come back.
 */
async function refreshSignalSetup() {
  try {
    const state = await api('/api/signal/state').then(r => r.json());
    signalSetupEl.hidden = !!state.configured;
  } catch {
    // Offline is not the moment to ask someone to set something up.
    signalSetupEl.hidden = true;
  }
}

signalPairBtnEl.addEventListener('click', async () => {
  const payload = signalPayloadEl.value.trim();
  if (!payload) return;
  signalSetupErrorEl.hidden = true;
  signalPairBtnEl.disabled = true;
  try {
    const res = await api('/api/signal/pair', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json; charset=utf-8' },
      body: JSON.stringify({ body: payload })
    });
    if (res.ok) {
      signalPayloadEl.value = '';
      signalSetupEl.hidden = true;
      // The threads list is about to gain a rail.
      loadThreads();
      return;
    }
    // Say what was wrong with it. "Failed" would not tell anyone they pasted half a line.
    const detail = await res.json().then(j => j && j.error).catch(() => null);
    signalSetupErrorEl.textContent = detail || 'that could not be used';
    signalSetupErrorEl.hidden = false;
  } catch {
    signalSetupErrorEl.textContent = 'could not reach the phone';
    signalSetupErrorEl.hidden = false;
  } finally {
    signalPairBtnEl.disabled = false;
  }
});

signalPayloadEl.addEventListener('keydown', e => {
  if (e.key === 'Enter') signalPairBtnEl.click();
});

refreshSignalSetup();

/*
 * With no token, ask for the six digits the phone is showing rather than a long link.
 * Everything bounding the code is on the phone; here it is one field and one attempt at a
 * time.
 */
function showPairCodePrompt(reason) {
  document.body.innerHTML =
    '<div id="pairGate">' +
    '  <h1>Messaging</h1>' +
    '  <p>' + (reason || 'Open Settings &rarr; Desktop Sync &rarr; Show link on your phone, and enter the code it shows.') + '</p>' +
    '  <input type="text" id="pairCode" inputmode="numeric" autocomplete="off" placeholder="000 000" maxlength="7">' +
    '  <button type="button" id="pairGo">Connect</button>' +
    '  <p id="pairErr" hidden></p>' +
    '</div>';
  const field = document.getElementById('pairCode');
  const err = document.getElementById('pairErr');
  const button = document.getElementById('pairGo');
  const go = async () => {
    const code = field.value.replace(/\D/g, '');
    if (code.length !== 6) return;
    button.disabled = true;
    err.hidden = true;
    try {
      const res = await fetch('/api/pair-code', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json; charset=utf-8',
          // Proves the request came from this page. A page on another origin cannot set a
          // custom header without a CORS preflight, which the relay does not answer -- so
          // it cannot spend this phone's pairing attempts from a tab the user happens to
          // have open somewhere else.
          'X-Kotozute-Pairing': '1'
        },
        body: JSON.stringify({ body: code })
      });
      if (res.ok) {
        const { token: t } = await res.json();
        rememberToken(t);
        location.reload();
        return;
      }
      const detail = await res.json().then(j => j && j.error).catch(() => null);
      err.textContent = detail || 'that code did not work';
      err.hidden = false;
    } catch {
      err.textContent = 'could not reach the phone';
      err.hidden = false;
    } finally {
      button.disabled = false;
    }
  };
  button.addEventListener('click', go);
  field.addEventListener('keydown', e => { if (e.key === 'Enter') go(); });
  field.focus();
}

if (!token) showPairCodePrompt();
