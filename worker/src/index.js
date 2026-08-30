/**
 * Fluid Transit — Worker di probe (Fase 1, spike 1).
 *
 * La domanda a cui questo Worker esiste per rispondere e' una sola:
 * l'origine `regionetoscana.smartregion.toscana.it` serve i feed anche
 * quando la richiesta parte dalla rete Cloudflare, o filtra gli IP
 * datacenter? Se filtra, il proxy previsto dal piano non e' realizzabile
 * e va deciso un piano B prima di scrivere codice di prodotto.
 *
 * Domande secondarie, tutte verificate qui perche' costano una riga in
 * piu' ciascuna e ognuna condiziona il design della Fase 4:
 *   - i byte che tornano sono un FeedMessage valido e fresco?
 *   - dall'edge Cloudflare l'origine manda ETag / Last-Modified / gzip
 *     (dall'IP residenziale no: ogni fetch e' integrale)?
 *   - una raffica di richieste ravvicinate viene rate-limitata?
 *   - lo statico da 129 MB supporta le richieste Range?
 */

import { peekFeed } from './gtfsrt-peek.js';

const ORIGIN = 'https://regionetoscana.smartregion.toscana.it/mobility/artifacts';
const RT_BASE = `${ORIGIN}/gtfs-rt`;
const STATIC_URL = `${ORIGIN}/gtfs`;

const FEEDS = ['vehicle-positions', 'trip-updates', 'alerts'];

// User-Agent identificativo: se qualcuno in Regione guarda i log deve poter
// capire chi siamo e come contattarci. Vale anche come cortesia minima verso
// un servizio pubblico gratuito.
const UA = 'FluidTransit-Probe/0.1 (+https://github.com/Casual76/Fluid-transit)';

/**
 * Cloudflare mette in cache le subrequest anche verso origini esterne. Per
 * misurare l'origine e non la cache dell'edge, ogni fetch della probe la
 * scavalca esplicitamente: TTL negativo = "non cachare".
 */
const NO_CACHE = {
  cacheTtlByStatus: { '200-299': -1, '300-399': -1, '400-599': -1 },
};

/** Header che decidono se le richieste condizionali sono possibili. */
const HEADERS_OF_INTEREST = [
  'content-type',
  'content-length',
  'content-encoding',
  'content-disposition',
  'etag',
  'last-modified',
  'cache-control',
  'expires',
  'age',
  'vary',
  'accept-ranges',
  'content-range',
  'server',
  'via',
  'date',
  'cf-cache-status',
  'x-kong-upstream-latency',
  'x-kong-request-id',
  'retry-after',
];

function collectHeaders(headers) {
  const out = {};
  for (const name of HEADERS_OF_INTEREST) {
    const value = headers.get(name);
    if (value !== null) out[name] = value;
  }
  return out;
}

function errorMessage(e) {
  return String(e && e.message ? e.message : e);
}

async function probeFeed(name, extraHeaders = {}) {
  const url = `${RT_BASE}/${name}`;
  const started = Date.now();
  const result = { feed: name, url };
  try {
    const res = await fetch(url, {
      headers: { 'User-Agent': UA, Accept: 'application/octet-stream', ...extraHeaders },
      cf: NO_CACHE,
    });
    result.status = res.status;
    result.statusText = res.statusText;
    result.headers = collectHeaders(res.headers);
    result.ttfbMs = Date.now() - started;

    if (res.status === 304) {
      result.notModified = true;
      result.totalMs = Date.now() - started;
      return result;
    }

    const body = new Uint8Array(await res.arrayBuffer());
    result.totalMs = Date.now() - started;
    // Il conteggio vero dei byte, non il content-length: l'origine risponde
    // in chunked e Cloudflare puo' aver decompresso per conto suo.
    result.bytes = body.length;

    const peek = peekFeed(body);
    result.protobufValid = peek.ok;
    if (!peek.ok) {
      result.protobufError = peek.error;
      // Se non e' protobuf puo' essere una pagina di blocco: i primi byte in
      // chiaro dicono subito se e' l'HTML di un WAF.
      result.bodyPreview = new TextDecoder().decode(body.subarray(0, 300));
    } else {
      result.entityCount = peek.entityCount;
      result.gtfsRealtimeVersion = peek.header ? peek.header.version : null;
      result.incrementality = peek.header ? peek.header.incrementality : 0;
      result.feedTimestamp = peek.header ? peek.header.timestamp : null;
      if (peek.header && peek.header.timestamp) {
        // L'eta' calcolata sul timestamp dell'ORIGINE, non sul momento del
        // poll: e' esattamente il valore che in Fase 4 diventa X-Feed-Age.
        result.feedAgeSeconds = Math.round(Date.now() / 1000) - peek.header.timestamp;
      }
    }
  } catch (e) {
    result.error = errorMessage(e);
    result.totalMs = Date.now() - started;
  }
  return result;
}

/** Tutti e tre i feed in parallelo, come fara' il cron della Fase 4. */
async function probeAll(request) {
  const started = Date.now();
  const feeds = await Promise.all(FEEDS.map((f) => probeFeed(f)));
  return {
    verdict: verdictOf(feeds),
    reachable: feeds.every((f) => f.status === 200),
    wallClockMs: Date.now() - started,
    colo: request.cf ? request.cf.colo : null,
    country: request.cf ? request.cf.country : null,
    checkedAt: new Date().toISOString(),
    feeds,
  };
}

function verdictOf(feeds) {
  if (feeds.every((f) => f.status === 200 && f.protobufValid)) {
    return 'OK - origine raggiungibile dalla rete Cloudflare, feed validi';
  }
  if (feeds.some((f) => f.status === 403 || f.status === 429 || f.status === 503)) {
    return 'BLOCCATO - l origine rifiuta la richiesta dall edge: design del proxy da rivedere';
  }
  if (feeds.some((f) => f.error)) {
    return 'ERRORE DI RETE - vedi campo error';
  }
  return 'PARZIALE - vedi i singoli feed';
}

/**
 * Le richieste condizionali sono possibili? Dall'IP residenziale l'origine
 * non manda ne' ETag ne' Last-Modified, quindi in teoria no. Qui si provano
 * comunque i validatori: un'origine che rispondesse 304 a `If-None-Match: *`
 * cambierebbe completamente il costo del cron.
 */
async function probeConditional() {
  const feed = 'vehicle-positions';
  const [wildcard, sinceNow] = await Promise.all([
    probeFeed(feed, { 'If-None-Match': '*' }),
    probeFeed(feed, { 'If-Modified-Since': new Date().toUTCString() }),
  ]);
  const honoured = wildcard.status === 304 || sinceNow.status === 304;
  return {
    question: 'l origine onora le richieste condizionali?',
    answer: honoured
      ? 'SI - almeno un validatore produce 304, il cron puo risparmiare banda'
      : 'NO - ogni fetch resta integrale, il proxy resta giustificato',
    ifNoneMatchWildcard: wildcard,
    ifModifiedSinceNow: sinceNow,
  };
}

/**
 * Raffica sequenziale. Il cron della Fase 4 colpisce l'origine 1.440 volte al
 * giorno da IP Cloudflare: se c'e' un rate limit va scoperto adesso.
 */
async function probeBurst(n) {
  const attempts = [];
  for (let i = 0; i < n; i++) {
    const r = await probeFeed('vehicle-positions');
    attempts.push({
      i,
      status: r.status,
      bytes: r.bytes === undefined ? null : r.bytes,
      totalMs: r.totalMs,
      feedTimestamp: r.feedTimestamp === undefined ? null : r.feedTimestamp,
      retryAfter: r.headers ? r.headers['retry-after'] || null : null,
      error: r.error || null,
    });
  }
  const throttled = attempts.filter((a) => a.status === 429 || a.status === 503);
  const distinctTimestamps = new Set(attempts.map((a) => a.feedTimestamp)).size;
  return {
    question: `${n} richieste consecutive vengono rate-limitate?`,
    answer:
      throttled.length === 0
        ? 'NO - nessuna risposta 429/503'
        : `SI - ${throttled.length} risposte rifiutate`,
    // Quanti timestamp distinti compaiono in una raffica dice ogni quanto
    // l'origine rigenera davvero il feed: se e' 1, il cron al minuto e' gia'
    // piu' fitto della sorgente.
    distinctFeedTimestamps: distinctTimestamps,
    attempts,
  };
}

/**
 * Lo statico: 129 MB scaricati ogni notte dalla GitHub Action, non dalla
 * Worker. Qui interessa solo sapere se supporta Range - sarebbe la
 * differenza fra riscaricare tutto e riprendere un download interrotto.
 */
async function probeStatic() {
  const started = Date.now();
  const out = { url: STATIC_URL };
  try {
    const res = await fetch(STATIC_URL, {
      method: 'GET',
      headers: { 'User-Agent': UA, Range: 'bytes=0-1023' },
      cf: NO_CACHE,
    });
    out.status = res.status;
    out.headers = collectHeaders(res.headers);
    out.rangeHonoured = res.status === 206;
    if (res.status === 206) {
      const body = new Uint8Array(await res.arrayBuffer());
      out.bytesReturned = body.length;
      // "PK\x03\x04" = firma di uno zip. Conferma che il primo KB e' davvero
      // l'inizio dell'archivio e non una pagina di errore.
      out.looksLikeZip =
        body[0] === 0x50 && body[1] === 0x4b && body[2] === 0x03 && body[3] === 0x04;
    } else if (res.status === 200) {
      // Il corpo integrale sarebbe 129 MB: non va letto dentro la Worker.
      out.note = 'Range ignorato: risposta integrale, un download interrotto riparte da zero';
      await res.body.cancel();
    }
  } catch (e) {
    out.error = errorMessage(e);
  }
  out.totalMs = Date.now() - started;
  return out;
}

const json = (data, status = 200) =>
  new Response(JSON.stringify(data, null, 2), {
    status,
    headers: {
      'content-type': 'application/json; charset=utf-8',
      'cache-control': 'no-store',
      // CC-BY: attribuzione anche sulle risposte macchina, come da piano.
      'x-data-source': 'Regione Toscana / Autolinee Toscane - CC-BY 4.0, dati modificati',
    },
  });

export default {
  async fetch(request) {
    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, '') || '/';

    switch (path) {
      case '/':
        return json({
          worker: 'fluid-transit-probe',
          scopo: 'Fase 1 / spike 1 - verificare che l origine risponda alla rete Cloudflare',
          endpoints: {
            '/probe': 'i tre feed RT in parallelo, con validazione protobuf ed eta del feed',
            '/probe/conditional': 'ETag / Last-Modified sono utilizzabili?',
            '/probe/burst?n=8': 'una raffica viene rate-limitata?',
            '/probe/static': 'lo zip GTFS supporta le richieste Range?',
            '/raw?feed=vehicle-positions': 'passthrough dei byte grezzi, per ispezione locale',
          },
          colo: request.cf ? request.cf.colo : null,
        });

      case '/probe': {
        const feed = url.searchParams.get('feed');
        if (feed) {
          if (!FEEDS.includes(feed)) {
            return json({ error: `feed sconosciuto: ${feed}`, noti: FEEDS }, 400);
          }
          return json(await probeFeed(feed));
        }
        return json(await probeAll(request));
      }

      case '/probe/conditional':
        return json(await probeConditional());

      case '/probe/burst': {
        const raw = parseInt(url.searchParams.get('n') || '5', 10) || 5;
        return json(await probeBurst(Math.min(Math.max(raw, 1), 20)));
      }

      case '/probe/static':
        return json(await probeStatic());

      case '/raw': {
        const feed = url.searchParams.get('feed') || 'vehicle-positions';
        if (!FEEDS.includes(feed)) {
          return json({ error: `feed sconosciuto: ${feed}`, noti: FEEDS }, 400);
        }
        const res = await fetch(`${RT_BASE}/${feed}`, {
          headers: { 'User-Agent': UA },
          cf: NO_CACHE,
        });
        return new Response(res.body, {
          status: res.status,
          headers: {
            'content-type': 'application/octet-stream',
            'cache-control': 'no-store',
            'x-origin-status': String(res.status),
          },
        });
      }

      default:
        return json({ error: 'not found', path }, 404);
    }
  },
};
