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

const token = new URLSearchParams(location.search).get('token') || '';
const threadsEl = document.getElementById('threads');
const messagesEl = document.getElementById('messages');
const paneTitleEl = document.getElementById('paneTitle');
const statusEl = document.getElementById('status');
const bodyEl = document.getElementById('body');
const composerEl = document.getElementById('composer');
const sendEl = document.getElementById('send');
const newBtnEl = document.getElementById('newBtn');
const toWrapEl = document.getElementById('toWrap');
const toFieldEl = document.getElementById('toField');
const suggestionsEl = document.getElementById('suggestions');
const searchFieldEl = document.getElementById('searchField');
const searchClearEl = document.getElementById('searchClear');
const attachEl = document.getElementById('attach');
const fileFieldEl = document.getElementById('fileField');
const attachmentsEl = document.getElementById('attachments');

let activeThreadId = null;
let activeThreadTitle = '';
let composeMode = false;
// The number actually sent to. Set when a suggestion is picked, so the visible
// field can show a friendly name while we still send to the real address.
let chosenAddress = null;
let suggestions = [];
let selIndex = -1;
let lookupTimer = null;
let lastThreads = [];
let lastMessagesSig = '';
let lastThreadsSig = '';
let filterQuery = '';
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

function enterComposeMode() {
  stashDraft(); // must run before composeMode/activeThreadId change out from under it
  composeMode = true;
  activeThreadId = null;
  activeThreadTitle = '';
  paneTitleEl.textContent = 'To:';
  toWrapEl.hidden = false;
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
  syncSearchClearButton();
  renderThreads();
}

searchFieldEl.addEventListener('input', () => {
  filterQuery = searchFieldEl.value;
  syncSearchClearButton();
  renderThreads();
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
  return fetch(path, opts);
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

async function loadThreads() {
  const res = await api('/api/threads');
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
    '#' + activeThreadId + '#' + filterQuery;
  if (sig === lastThreadsSig) return;
  lastThreadsSig = sig;
  renderThreads();
}

/** Draw the (optionally filtered) conversation list from whatever we last fetched. */
function renderThreads() {
  const prevScroll = threadsEl.scrollTop;
  const q = filterQuery.trim().toLowerCase();
  const threads = !q ? lastThreads : lastThreads.filter(t =>
    (t.title || '').toLowerCase().includes(q) || (t.snippet || '').toLowerCase().includes(q));

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
    row.append(name, when);
    const snippet = document.createElement('div');
    applySnippet(snippet, t, drafts[String(t.id)]);
    div.dataset.id = t.id; // lets a keystroke update just this row, see refreshDraftRow
    div.append(row, snippet);
    div.addEventListener('click', () => selectThread(t.id, t.title));
    threadsEl.append(div);
  });
  threadsEl.scrollTop = prevScroll;
}

async function selectThread(id, title) {
  stashDraft(); // capture unsent text for the thread we're leaving, before it changes
  exitComposeMode();
  lastMessagesSig = ''; // force a fresh render for the newly opened thread
  messageLimit = 300;    // start each thread at the most recent page
  hasMoreMessages = false;
  activeThreadId = id;
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
}

/** Tell the phone this thread has been read (clears its notification + badge). */
async function markThreadRead(id) {
  if (id === null || id === undefined) return;
  try {
    await api('/api/threads/' + id + '/read', { method: 'POST' });
  } catch (e) {
    console.warn('mark read failed', e);
  }
}

async function loadMessages() {
  if (activeThreadId === null) return;
  const res = await api('/api/threads/' + activeThreadId + '/messages?limit=' + messageLimit);
  if (!res.ok) return;
  const payload = await res.json();
  // Response used to be a bare array; it's now {total, hasMore, messages}
  const messages = Array.isArray(payload) ? payload : (payload.messages || []);
  hasMoreMessages = Array.isArray(payload) ? false : !!payload.hasMore;

  // The poll runs every few seconds. Rebuilding the DOM each time would throw away
  // the reader's scroll position (and any in-flight image loads), so bail out when
  // nothing has actually changed.
  const sig = activeThreadId + ':' + messageLimit + ':' + messages.length + ':' +
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

  messages.forEach(m => {
    const wrap = document.createElement('div');
    wrap.className = 'msg' + (m.isMe ? ' me' : '');
    const inner = document.createElement('div');
    inner.className = 'stack';
    const bubble = document.createElement('div');
    bubble.className = 'bubble';
    const text = (m.body || '').trim();
    if (text) bubble.textContent = text;

    // MMS attachments — without these a picture message is just an empty bubble.
    (m.attachments || []).forEach(att => {
        if (att.isImage) {
          const img = document.createElement('img');
          img.className = 'attach';
          // Deliberately NOT loading="lazy": the bubble starts at zero height, so the
          // lazy heuristic never fires and the image sits at complete=false forever.
          img.alt = att.label || 'Picture';
          // Token goes in the query string: <img src> can't send an auth header.
          img.src = '/api/parts/' + att.id + '?token=' + encodeURIComponent(token);
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
        } else {
          const link = document.createElement('a');
          link.className = 'attachLink';
          link.href = '/api/parts/' + att.id + '?token=' + encodeURIComponent(token);
          link.target = '_blank';
          link.rel = 'noopener';
          link.textContent = '📎 ' + (att.label || att.type);
          bubble.append(link);
        }
      });

    if (!text && !(m.attachments || []).length) bubble.textContent = '';
    const stamp = document.createElement('div');
    stamp.className = 'stamp';
    stamp.textContent = formatTime(m.date);
    inner.append(bubble, stamp);
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
    const res = await api('/api/compose', Object.assign(
      { method: 'POST' },
      sendRequestBody({ to: to, body: text })
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
connectSocket();

// Belt-and-braces polling alongside the WebSocket. The socket makes updates
// near-instant when it's healthy, but it can go quiet after laptop sleep, a
// network switch, or a dropped push — polling guarantees the view still catches
// up on its own without a manual reload.
setInterval(refresh, 5000);
