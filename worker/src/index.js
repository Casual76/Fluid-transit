/**
 * Fluid Transit — il proxy realtime (Fase 4).
 *
 * Principio: ZERO parsing sul percorso richiesta. Il piano gratuito da'
 * ~10 ms di CPU a richiesta ma 30 s a invocazione cron, quindi:
 *
 *   cron (1/min)  = fetch dei 3 feed + decoder protobuf statico + snapshot
 *                   binario compatto scritto UNA volta su R2 (rt/latest.bin);
 *   richiesta     = lettura R2 + copia di intervalli di byte gia' pronti,
 *                   dietro la Cache API con max-age 35 s.
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

/**
 * La voce di cache deve sopravvivere al poll SUCCESSIVO dell'app, che arriva
 * a 30 s (RealtimeClient.vehiclesIntervalMs). A 25 s scadeva cinque secondi
 * prima, quindi il tasso di hit era strutturalmente zero e ogni richiesta
 * ripagava lettura R2 + slice + gzip. L'eta' del feed non ne soffre: da qui
 * in giu' `x-feed-age` si ricalcola al momento della risposta, non e' piu'
 * quella congelata dentro la voce.
 */
const MAX_AGE_SECONDS = 35;

/**
 * Le tre sezioni, affettate e compresse UNA volta dal cron.
 *
 * Prima ogni richiesta leggeva `rt/latest.bin` per intero — circa 400 kB, di
 * cui 294 sono gli alerts — per poi affettarne 30 e ricomprimerli. Chi
 * chiedeva i veicoli pagava gli avvisi di servizio, ogni volta, e il gzip si
 * rifaceva a ogni scadenza della cache dell'edge.
 *
 * Ora la richiesta e' una lettura da qualche kilobyte e una copia. Lo
 * snapshot intero resta scritto: serve al confronto dei timestamp del giro
 * dopo (una range read da 64 byte) e come ripiego finche' le sezioni non
 * esistono.
 */
const SECTION_KEYS = {
  1: 'rt/vehicles.gz',
  2: 'rt/updates.gz',
  3: 'rt/alerts.gz',
};
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
  const now = Math.floor(Date.now() / 1000);
  const age = now - (generatedAt || 0);
  if (age < LAZY_REFRESH_AFTER_SECONDS || refreshInFlight) return;
  // La guardia che mancava. `refreshInFlight` copre solo le richieste
  // CONCORRENTI; due poll a 30 s di distanza facevano due giri completi.
  // E siccome il ramo 'invariato' non riscrive lo snapshot, `generatedAt`
  // avanza solo quando l'origine si muove davvero (~120 s): l'eta' restava
  // sopra soglia per la maggior parte del tempo, e ogni richiesta in quella
  // finestra rifaceva 3 fetch + parse + build + put. Era il moltiplicatore
  // piu' grosso della bolletta CPU.
  if (now - lastRefreshAt < LAZY_REFRESH_AFTER_SECONDS) return;
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
          // sharedRefresh, non refresh: e' l'unico punto che aggiorna
          // `lastRefreshAt`, il guardiano che tiene disarmati il refresh
          // pigro e quello bloccante subito dopo un giro appena fatto.
          outcome = await sharedRefresh(env);
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
      case '/rt/v1/refresh': return serveRefresh(request, env);
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
 *
 * Costa quanto un tick di cron, e siccome il Cron Trigger di Cloudflare non
 * e' mai scattato questo endpoint E' il motore di refresh vero (lo chiama il
 * keepalive su GitHub Actions). Due conseguenze che prima non erano gestite:
 * passa da `sharedRefresh` come tutti gli altri, e vuole un segreto — l'URL
 * sta in chiaro dentro un workflow pubblico, e chiunque poteva far partire
 * un giro completo a piacimento.
 *
 * Finche' `REFRESH_SECRET` non e' configurato l'endpoint resta aperto: il
 * keepalive non deve rompersi nell'intervallo fra questo deploy e la messa
 * in opera del segreto.
 */
async function serveRefresh(request, env) {
  const secret = env.REFRESH_SECRET;
  if (secret) {
    const url = new URL(request.url);
    const given = request.headers.get('x-refresh-key') || url.searchParams.get('key');
    if (given !== secret) {
      return new Response(JSON.stringify({ ok: false, error: 'non autorizzato' }), {
        status: 401,
        headers: { 'content-type': 'application/json', 'cache-control': 'no-store' },
      });
    }
  }
  try {
    const outcome = await sharedRefresh(env);
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

  // PRIMA i soli tre timestamp, POI — solo se e' cambiato qualcosa — i due
  // parse integrali.
  //
  // `parseFeed(x, 'header')` attraversa il buffer ma non entra in nessuna
  // entita': la condizione `want !== 'header'` in gtfsrt.js le manda tutte
  // su skipField, che e' un varint e un salto. Prima il confronto stava a
  // valle dei parse completi, quindi sulla meta' dei giri in cui l'origine
  // non si era mossa si decodificavano migliaia di VehiclePosition e di
  // TripUpdate per poi buttarli.
  let vpTs;
  let tuTs;
  let alTimestamp;
  try {
    vpTs = parseFeed(vpBytes, 'header').timestamp || 0;
    tuTs = parseFeed(tuBytes, 'header').timestamp || 0;
    alTimestamp = parseFeed(alBytes, 'header').timestamp || 0;
  } catch (e) {
    console.log('header illeggibile, snapshot non toccato:', String(e));
    return 'header illeggibile: ' + String(e);
  }

  // L'origine si rigenera ogni ~2 minuti: se niente e' cambiato, niente
  // scrittura — e' la meta' di operazioni di classe A che il piano prevede
  // di risparmiare, e adesso anche la meta' dei parse.
  if (
    prev &&
    prev.vpTimestamp === vpTs &&
    prev.tuTimestamp === tuTs &&
    prev.alTimestamp === alTimestamp
  ) {
    return 'invariato: timestamp identici, nessuna scrittura';
  }

  let vp;
  let tu;
  try {
    vp = parseFeed(vpBytes, 'vehicles');
    tu = parseFeed(tuBytes, 'updates');
  } catch (e) {
    console.log('parse fallito, snapshot non toccato:', String(e));
    return 'parse fallito: ' + String(e);
  }

  const snapshot = buildSnapshot({
    generatedAt: Math.floor(Date.now() / 1000),
    vp,
    tu,
    alertsBytes: alBytes,
    // Gia' letto qui sopra: senza questo buildSnapshot rifarebbe il parse
    // dell'header degli alerts per estrarre lo stesso numero.
    alTimestamp,
    flags: 0,
  });
  await env.RT.put(SNAPSHOT_KEY, snapshot);
  await writeSections(env, snapshot);
  return 'scritto: ' + vp.vehicles.length + ' veicoli, ' + tu.updates.length + ' update, ' + snapshot.length + ' B';
}

/**
 * Le tre sezioni pronte da servire. I metadati viaggiano con l'oggetto:
 * servono a costruire ETag ed eta' senza rileggere niente altro.
 */
async function writeSections(env, snapshot) {
  const header = readHeader(snapshot);
  if (!header) return;
  const parts = [
    [1, sliceSection(snapshot, header, 1), header.vpTimestamp],
    [2, sliceSection(snapshot, header, 2), header.tuTimestamp],
    [3, snapshot.subarray(header.alertsOff, header.alertsOff + header.alertsLen), header.alTimestamp],
  ];
  await Promise.all(parts.map(async ([kind, body, feedTs]) => {
    const gz = await gzipBytes(body);
    await env.RT.put(SECTION_KEYS[kind], gz, {
      customMetadata: {
        feedTs: String(feedTs || 0),
        len: String(body.length),
        gen: String(header.generatedAt || 0),
      },
    });
  }));
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

  // La via veloce: la sezione gia' affettata e gia' compressa. Nessun parse,
  // nessun gzip, e si leggono i byte di QUESTA sezione invece dei 400 kB
  // dello snapshot intero.
  if (!response) {
    const direct = await env.RT.get(SECTION_KEYS[kind]);
    if (direct) {
      const meta = direct.customMetadata || {};
      const feedTs = Number(meta.feedTs || 0);
      const bodyLen = Number(meta.len || 0);
      const generatedAt = Number(meta.gen || 0);
      const gz = new Uint8Array(await direct.arrayBuffer());
      const headers = new Headers({
        'content-type': kind === 3 ? 'application/x-protobuf' : 'application/octet-stream',
        'content-encoding': 'gzip',
        'cache-control': `public, max-age=${MAX_AGE_SECONDS}`,
        etag: `W/"${(feedTs || 0).toString(16)}-${bodyLen.toString(16)}"`,
        'x-data-source': ATTRIBUTION,
        'x-snapshot-generated': String(generatedAt),
      });
      const age = feedAgeHeader(feedTs);
      if (age !== null) headers.set('x-feed-age', age);
      if (feedTs) headers.set('x-feed-timestamp', String(feedTs));
      response = new Response(gz, { headers, encodeBody: 'manual' });
      ctx.waitUntil(cache.put(cacheKey, response.clone()));
      // Il refresh resta agganciato all'eta' dello snapshot, come prima.
      maybeLazyRefresh(env, ctx, generatedAt);
    }
  }

  // Il ripiego: lo snapshot intero. Vale finche' le sezioni non sono state
  // scritte nemmeno una volta — cioe' dal deploy al primo giro di cron.
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
        const outcome = await sharedRefresh(env);
        // Si rilegge SOLO se il giro ha scritto davvero. Sugli esiti
        // 'invariato' e 'feed non raggiunti' i byte su R2 sono per
        // definizione identici a quelli gia' in mano: rileggerli erano
        // ~400 KB buttati proprio nel caso in cui questo ramo scatta piu'
        // spesso (origine ferma, eta' fra 90 e 120 s).
        if (typeof outcome === 'string' && outcome.startsWith('scritto')) {
          const again = await env.RT.get(SNAPSHOT_KEY);
          if (again) {
            const fresher = new Uint8Array(await again.arrayBuffer());
            const reread = readHeader(fresher);
            if (reread) {
              snapshot = fresher;
              header = reread;
            }
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
    // Il timestamp nudo viaggia insieme all'eta': e' quello che permette di
    // ricalcolarla in uscita anche quando la risposta arriva dalla cache.
    if (feedTs) headers.set('x-feed-timestamp', String(feedTs));

    response = new Response(gz, { headers, encodeBody: 'manual' });
    ctx.waitUntil(cache.put(cacheKey, response.clone()));
  }

  // L'eta' si ricalcola SEMPRE al momento della risposta. Quella scritta
  // dentro la voce di cache e' l'eta' di quando la voce e' nata, e fino a
  // MAX_AGE_SECONDS dopo sarebbe sotto-dichiarata: l'app decide su questo
  // numero se fidarsi del live (RealtimeClient.STALE_SECONDS), quindi deve
  // essere vero adesso, non allora.
  const feedTsOut = Number(response.headers.get('x-feed-timestamp') || 0);
  const freshAge = feedTsOut ? feedAgeHeader(feedTsOut) : null;

  // Richieste condizionali dell'app: il 304 costa zero byte. Porta comunque
  // l'eta', che e' esattamente il caso in cui conta di piu': senza, l'app
  // ripiegava sull'orologio del telefono per calcolarla.
  const inm = request.headers.get('if-none-match');
  const etag = response.headers.get('etag');
  if (inm && etag && inm === etag) {
    const h = new Headers({ etag });
    if (freshAge !== null) h.set('x-feed-age', freshAge);
    return new Response(null, { status: 304, headers: h });
  }
  if (freshAge === null) return response;
  const out = new Headers(response.headers);
  out.set('x-feed-age', freshAge);
  return new Response(response.body, {
    status: response.status,
    headers: out,
    encodeBody: 'manual',
  });
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
