/**
 * Wiki Agent — answers questions about the wiki.
 *
 * Default mode is fully OFFLINE: it searches the locally cached pages (plus the
 * server when online), scores sentences by keyword overlap with the query, and
 * returns a short synthesized answer with citations to the source pages.
 *
 * If an AI endpoint is configured in config.js (OpenAI-compatible chat
 * completions), it will instead send the top-matching passages as context and
 * return the model's answer — still citing the pages it read.
 */
import * as db from './db.js';
import { api, isOnline } from './api.js';
import { CONFIG } from './config.js';

function tokenize(text) {
  return (text || '')
    .toLowerCase()
    .replace(/[^a-zа-я0-9\s]/gi, ' ')
    .split(/\s+/)
    .filter((w) => w.length > 2);
}

function scorePassage(queryTokens, passage) {
  const pTokens = tokenize(passage);
  const set = new Set(pTokens);
  let score = 0;
  for (const q of queryTokens) if (set.has(q)) score += 1;
  // light boost for term frequency
  const counts = {};
  pTokens.forEach((t) => (counts[t] = (counts[t] || 0) + 1));
  for (const q of queryTokens) if (counts[q]) score += 0.1 * (counts[q] - 1);
  return score;
}

function splitSentences(text) {
  return (text || '').split(/(?<=[.!?])\s+/).map((s) => s.trim()).filter(Boolean);
}

async function gatherContext(query, limit = 8) {
  let pages = await db.getAllPages();
  if (isOnline() && pages.length < 3) {
    // pull a bit more if we are online and cache is thin
    try {
      const more = await api.syncSince((await db.getMeta('lastPull')) || '');
      pages = pages.concat(more);
    } catch {}
  }
  const qTokens = tokenize(query);
  const passages = [];
  for (const page of pages) {
    const sentences = splitSentences(page.body);
    for (const s of sentences) {
      const sc = scorePassage(qTokens, s);
      if (sc > 0) passages.push({ score: sc, pageId: page.id, pageTitle: page.title, text: s });
    }
  }
  passages.sort((a, b) => b.score - a.score);
  return passages.slice(0, limit);
}

function synthesize(query, passages) {
  if (!passages.length) {
    return {
      answer: 'I could not find anything about that in the pages I have cached. ' +
        'Open the relevant space online or sync to load more content, then ask again.',
      citations: []
    };
  }
  // Group best passages by page for a tidy answer.
  const byPage = new Map();
  passages.forEach((p) => {
    if (!byPage.has(p.pageId)) byPage.set(p.pageId, []);
    byPage.get(p.pageId).push(p);
  });
  const lines = [];
  let i = 1;
  const citations = [];
  for (const [pageId, ps] of byPage) {
    const top = ps[0];
    citations.push({ pageId, pageTitle: top.pageTitle });
    lines.push(`${i}. ${top.text}  [${top.pageTitle}]`);
    i++;
  }
  const answer = `Based on the wiki I have available:\n\n${lines.join('\n')}`;
  return { answer, citations };
}

export async function askAgent(query) {
  const passages = await gatherContext(query);

  if (CONFIG.AGENT_API_URL && CONFIG.AGENT_API_KEY) {
    try {
      const context = passages
        .map((p, idx) => `[${idx + 1}] (${p.pageTitle}): ${p.text}`)
        .join('\n');
      const system = 'You are a helpful wiki assistant. Answer the user\'s question ' +
        'using ONLY the provided excerpts. Cite sources by their bracketed number, ' +
        'e.g. [1]. If the excerpts do not contain the answer, say so.';
      const user = `Question: ${query}\n\nExcerpts:\n${context || '(none)'}`;
      const res = await fetch(CONFIG.AGENT_API_URL, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${CONFIG.AGENT_API_KEY}` },
        body: JSON.stringify({
          model: CONFIG.AGENT_MODEL,
          messages: [
            { role: 'system', content: system },
            { role: 'user', content: user }
          ],
          temperature: 0.2
        })
      });
      if (res.ok) {
        const data = await res.json();
        const answer = data.choices?.[0]?.message?.content || synthesize(query, passages).answer;
        return { answer, citations: passages.map((p) => ({ pageId: p.pageId, pageTitle: p.pageTitle })).slice(0, 5) };
      }
    } catch {
      // fall through to offline synthesis
    }
  }
  return synthesize(query, passages);
}

/* Convenience: summarize a single page. */
export async function summarizePage(pageId) {
  const page = await db.getPage(pageId);
  if (!page) return { answer: 'Page not found locally.', citations: [] };
  return askAgent(`Summarize the key points of: ${page.title}. ${page.body.slice(0, 200)}`);
}
