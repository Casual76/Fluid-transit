/**
 * Fluid Transit — il proxy realtime (Fase 4).
 *
 * Principio: ZERO parsing sul percorso richiesta. Il piano gratuito da'
 * ~10 ms di CPU a richiesta ma 30 s a invocazione cron, quindi:
 *
 *   cron (1/min)  = fetch dei 3 feed + decoder protobuf statico + snapshot
 *                   binario compatto scritto UNA volta su R2 (rt/latest.bin);
 *   richiesta     = lettura R2 + copia di intervalli di byte gia' pronti,
 *                   dietro la Cache API con max-age 45 s.
 *
 * L'origine si rigenera ogni ~2 minuti e non manda validatori: meta' dei
 * poll sono ridondanti e non si possono evitare — ma la SCRITTURA si evita:
 * se i tre timestamp non sono cambiati, il cron non riscrive niente e le
 * operazioni di classe A su R2 si dimezzano.
 *
 * Gli id del feed viaggiano come hash FNV-1a 64 (identici al bundle): l'app
 * risolve i record via TRIP_ID_INDEX / routeIdHash senza stringhe.
 */

import { parseFeed } from './gtfsrt.js';
import {
  SNAPSHOT_KEY, HEADER_LEN, buildSnapshot, readHeader, sliceSection,
} from './snapshot.js';

const ORIGIN = 'https://regionetoscana.smartregion.toscana.it/mobility/artifacts/gtfs-rt';
const UA = 'FluidTransit-RT/1.0 (+https://github.com/Casual76/Fluid-transit)';
const ATTRIBUTION =
  'Dati: Regione Toscana / Autolinee Toscane, CC-BY 4.0 - dati modificati (aggregati e ricodificati)';

/** La cache dell'edge sui fetch verso l'origine e' inutile qui: niente validatori. */
const NO_CACHE = {
  cacheTtlByStatus: { '200-299': -1, '300-399': -1, '400-599': -1 },
};

const MAX_AGE_SECONDS = 25;
const HEARTBEAT_KEY = 'rt/cron-heartbeat';

/**
 * Refresh pigro: se una richiesta scopre uno snapshot piu' vecchio di cosi',
 * ne avvia uno in waitUntil. E' la cintura oltre alle bretelle del cron —
 * osservato sul campo: la schedule risulta registrata ma le prime esecuzioni
 * possono tardare. Con l'app che polla ogni 30 s, basta un utente perche'
 * il proxy si tenga fresco da solo.
 */
const LAZY_REFRESH_AFTER_SECONDS = 55;

/**
 * Oltre questa eta' non si serve piu' il vecchio rinfrescando per il
 * PROSSIMO: si rinfresca PRIMA di rispondere.
 *
 * Il motivo, misurato il 03/09: il cron di Cloudflare non e' un metronomo
 * (ultimo battito 26 minuti prima) e il keepalive su GitHub, programmato
 * ogni 5 minuti, parte in realta' ogni 2-5 ore. Restava in piedi solo il
 * refresh pigro, che pero' dava al richiedente le posizioni vecchie e
 * rinfrescava per chi veniva dopo: con un utente solo, quel "dopo" non
 * arrivava mai prima di un giro di poll. Aspettare due secondi e' meglio
 * che mostrare mezz'ora di ritardo.
 */
const BLOCKING_REFRESH_AFTER_SECONDS = 90;

/** Il refresh in corso, condiviso: le richieste in parallelo non ne fanno tre. */
let refreshInFlight = null;

/** Quando questo isolate ha interrogato l'origine l'ultima volta. */
let lastRefreshAt = 0;

function sharedRefresh(env) {
  if (!refreshInFlight) {
    refreshInFlight = refresh(env).finally(() => {
      refreshInFlight = null;
      lastRefreshAt = Math.floor(Date.now() / 1000);
    });
  }
  return refreshInFlight;
}

function maybeLazyRefresh(env, ctx, generatedAt) {
  const age = Math.floor(Date.now() / 1000) - (generatedAt || 0);
  if (age < LAZY_REFRESH_AFTER_SECONDS || refreshInFlight) return;
  ctx.waitUntil(sharedRefresh(env).catch(() => {}));
}

export default {
  async scheduled(controller, env, ctx) {
    // Il battito: si scrive a OGNI invocazione, qualunque sia l'esito.
    // Distingue "il cron non parte" da "parte e fallisce" — senza log
    // persistenti e' l'unico testimone, e /rt/v1/health lo riporta.
    ctx.waitUntil(
      (async () => {
        let outcome;
        try {
          outcome = await refresh(env);
        } catch (e) {
          outcome = 'errore: ' + String(e);
        }
        await env.RT.put(
          HEARTBEAT_KEY,
          JSON.stringify({ at: Math.floor(Date.now() / 1000), outcome }),
        );
      })(),
    );
  },

  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    switch (url.pathname) {
      case '/rt/v1/vehicles': return serveSection(request, env, ctx, 1);
      case '/rt/v1/updates': return serveSection(request, env, ctx, 2);
      case '/rt/v1/alerts': return serveSection(request, env, ctx, 3);
      case '/rt/v1/health': return serveHealth(env, ctx);
      case '/rt/v1/refresh': return serveRefresh(env);
      default:
        return new Response('Fluid Transit realtime proxy. Endpoints: /rt/v1/{vehicles,updates,alerts,health}\n', {
          status: url.pathname === '/' ? 200 : 404,
          headers: { 'content-type': 'text/plain; charset=utf-8' },
        });
    }
  },
};

// --- cron -------------------------------------------------------------------

async function fetchFeed(name) {
  const res = await fetch(`${ORIGIN}/${name}`, {
    headers: { 'User-Agent': UA, Accept: 'application/octet-stream' },
    cf: NO_CACHE,
  });
  if (!res.ok) throw new Error(`${name}: HTTP ${res.status}`);
  return new Uint8Array(await res.arrayBuffer());
}

/**
 * Lo stesso lavoro del cron, a comando: per il debug e per forzare un giro.
 * Costa quanto un tick di cron; niente da proteggere.
 */
async function serveRefresh(env) {
  try {
    const outcome = await refresh(env);
    return new Response(JSON.stringify({ ok: true, outcome }, null, 2), {
      headers: { "content-type": "application/json", "cache-control": "no-store" },
    });
  } catch (e) {
    return new Response(
      JSON.stringify({ ok: false, error: String(e), stack: e && e.stack }, null, 2),
      { status: 500, headers: { "content-type": "application/json", "cache-control": "no-store" } },
    );
  }
}

async function refresh(env) {
  // I timestamp dello snapshot precedente: una range read da 64 byte.
  let prev = null;
  try {
    const head = await env.RT.get(SNAPSHOT_KEY, { range: { offset: 0, length: HEADER_LEN } });
    if (head) prev = readHeader(new Uint8Array(await head.arrayBuffer()));
  } catch {
    prev = null;
  }

  const results = await Promise.allSettled([
    fetchFeed('vehicle-positions'),
    fetchFeed('trip-updates'),
    fetchFeed('alerts'),
  ]);

  // Un feed mancato = si tiene lo snapshot precedente intero. Meglio dati
  // di un minuto fa, coerenti fra loro, che uno snapshot mezzo vuoto: la
  // staleness la denuncia X-Feed-Age, non un buco nei record.
  if (results.some((r) => r.status === 'rejected')) {
    console.log('feed non raggiunti:', results
      .map((r, i) => (r.status === 'rejected' ? `${i}:${r.reason}` : null))
      .filter(Boolean)
      .join(' | '));
    return 'feed non raggiunti';
  }

  const [vpBytes, tuBytes, alBytes] = results.map((r) => r.value);

  let vp;
  let tu;
  let alTimestamp = 0;
  try {
    vp = parseFeed(vpBytes, 'vehicles');
    tu = parseFeed(tuBytes, 'updates');
    alTimestamp = parseFeed(alBytes, 'header').timestamp || 0;
  } catch (e) {
    console.log('parse fallito, snapshot non toccato:', String(e));
    return 'parse fallito: ' + String(e);
  }

  // L'origine si rigenera ogni ~2 minuti: se niente e' cambiato, niente
  // scrittura — e' la meta' di operazioni di classe A che il piano prevede
  // di risparmiare.
  if (
    prev &&
    prev.vpTimestamp === (vp.timestamp || 0) &&
    prev.tuTimestamp === (tu.timestamp || 0) &&
    prev.alTimestamp === alTimestamp
  ) {
    return 'invariato: timestamp identici, nessuna scrittura';
  }

  const snapshot = buildSnapshot({
    generatedAt: Math.floor(Date.now() / 1000),
    vp,
    tu,
    alertsBytes: alBytes,
    flags: 0,
  });
  await env.RT.put(SNAPSHOT_KEY, snapshot);
  return 'scritto: ' + vp.vehicles.length + ' veicoli, ' + tu.updates.length + ' update, ' + snapshot.length + ' B';
}

// --- richieste --------------------------------------------------------------

async function gzipBytes(bytes) {
  const stream = new Blob([bytes]).stream().pipeThrough(new CompressionStream('gzip'));
  return new Uint8Array(await new Response(stream).arrayBuffer());
}

function feedAgeHeader(feedTs) {
  if (!feedTs) return null;
  return String(Math.max(0, Math.floor(Date.now() / 1000) - feedTs));
}

/**
 * kind: 1 = vehicles, 2 = updates (entrambi record fissi col mini-header),
 * 3 = alerts (il FeedMessage grezzo: l'app oggi non lo usa, ma il costo di
 * servirlo e' zero e la Fase 6/8 lo trovera' gia' qui).
 */
async function serveSection(request, env, ctx, kind) {
  const cache = caches.default;
  const cacheKey = new Request(new URL(request.url).origin + new URL(request.url).pathname);

  let response = await cache.match(cacheKey);
  if (!response) {
    const obj = await env.RT.get(SNAPSHOT_KEY);
    if (!obj) {
      return new Response(JSON.stringify({ error: 'snapshot non ancora generato' }), {
        status: 503,
        headers: { 'content-type': 'application/json', 'retry-after': '60' },
      });
    }
    let snapshot = new Uint8Array(await obj.arrayBuffer());
    let header = readHeader(snapshot);
    if (!header) {
      return new Response(JSON.stringify({ error: 'snapshot corrotto' }), {
        status: 503,
        headers: { 'content-type': 'application/json', 'retry-after': '60' },
      });
    }

    const nowSec = Math.floor(Date.now() / 1000);
    const snapshotAge = nowSec - (header.generatedAt || 0);
    if (
      snapshotAge >= BLOCKING_REFRESH_AFTER_SECONDS &&
      nowSec - lastRefreshAt >= BLOCKING_REFRESH_AFTER_SECONDS
    ) {
      // Si aspetta il giro. Il guardiano `lastRefreshAt` serve al caso
      // 'invariato': se l'origine non si e' mossa, `generatedAt` non
      // avanza, e senza questo si bloccherebbe ogni richiesta su una
      // rilettura che non cambia niente.
      try {
        await sharedRefresh(env);
        const again = await env.RT.get(SNAPSHOT_KEY);
        if (again) {
          const fresher = new Uint8Array(await again.arrayBuffer());
          const reread = readHeader(fresher);
          if (reread) {
            snapshot = fresher;
            header = reread;
          }
        }
      } catch {
        // Origine giu': si serve quello che si ha, meglio di un errore.
      }
    } else {
      maybeLazyRefresh(env, ctx, header.generatedAt);
    }

    let body;
    let contentType = 'application/octet-stream';
    let feedTs;
    if (kind === 3) {
      body = snapshot.subarray(header.alertsOff, header.alertsOff + header.alertsLen);
      contentType = 'application/x-protobuf';
      feedTs = header.alTimestamp;
    } else {
      body = sliceSection(snapshot, header, kind);
      feedTs = kind === 1 ? header.vpTimestamp : header.tuTimestamp;
    }

    const gz = await gzipBytes(body);
    const headers = new Headers({
      'content-type': contentType,
      'content-encoding': 'gzip',
      'cache-control': `public, max-age=${MAX_AGE_SECONDS}`,
      // L'ETag e' della SEZIONE, non dello snapshot: con `generatedAt`
      // dentro, la risposta dei veicoli cambiava validatore anche quando si
      // muovevano solo i trip-updates, e l'app rifaceva tutto il giro
      // (parse, resolve, nuovo StateFlow) per byte identici.
      etag: `W/"${(feedTs || 0).toString(16)}-${body.length.toString(16)}"`,
      'x-data-source': ATTRIBUTION,
      'x-snapshot-generated': String(header.generatedAt),
    });
    const age = feedAgeHeader(feedTs);
    if (age !== null) headers.set('x-feed-age', age);

    response = new Response(gz, { headers, encodeBody: 'manual' });
    ctx.waitUntil(cache.put(cacheKey, response.clone()));
  }

  // Richieste condizionali dell'app: il 304 costa zero byte.
  const inm = request.headers.get('if-none-match');
  const etag = response.headers.get('etag');
  if (inm && etag && inm === etag) {
    return new Response(null, { status: 304, headers: { etag } });
  }
  return response;
}

async function serveHealth(env, ctx) {
  let heartbeat = null;
  try {
    const hb = await env.RT.get(HEARTBEAT_KEY);
    if (hb) heartbeat = JSON.parse(await hb.text());
  } catch {
    heartbeat = null;
  }
  let header = null;
  try {
    const head = await env.RT.get(SNAPSHOT_KEY, { range: { offset: 0, length: HEADER_LEN } });
    if (head) header = readHeader(new Uint8Array(await head.arrayBuffer()));
  } catch {
    header = null;
  }
  const now = Math.floor(Date.now() / 1000);
  if (header) maybeLazyRefresh(env, ctx, header.generatedAt);
  const body = header
    ? {
      ok: true,
      generatedAt: header.generatedAt,
      snapshotAgeSeconds: now - header.generatedAt,
      vehicles: { count: header.vehicleCount, feedAgeSeconds: header.vpTimestamp ? now - header.vpTimestamp : null },
      updates: { count: header.delayCount, feedAgeSeconds: header.tuTimestamp ? now - header.tuTimestamp : null },
      alerts: { bytes: header.alertsLen, feedAgeSeconds: header.alTimestamp ? now - header.alTimestamp : null },
      cron: heartbeat ? { ageSeconds: now - heartbeat.at, outcome: heartbeat.outcome } : null,
    }
    : { ok: false, error: 'snapshot non ancora generato' };
  return new Response(JSON.stringify(body, null, 2), {
    status: header ? 200 : 503,
    headers: {
      'content-type': 'application/json',
      'cache-control': 'no-store',
      'x-data-source': ATTRIBUTION,
    },
  });
}
