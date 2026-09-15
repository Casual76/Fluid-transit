/**
 * Le previsioni per fermata: la sezione che risponde a "i vostri minuti non
 * sono quelli ufficiali".
 *
 * Il feed `trip-updates` pubblica, per ogni corsa monitorata, un
 * StopTimeUpdate per ogni fermata rimanente. Fino alla Fase 9 di tutto quel
 * ventaglio ne tenevamo **uno**, il primo con un ritardo, e l'app doveva
 * inventarsi come quel numero si propagasse lungo il percorso. Le app
 * ufficiali usano le previsioni cosi' come sono. Questa sezione le porta
 * fino all'app intere.
 *
 * ## Quanto segnale c'era davvero
 *
 * Misurato sul feed vero il 15/09/2026: 307 corse, 4.415 fermate, 4.109 con
 * un ritardo dichiarato. **Lo stesso ritardo non si ripete mai**: fra due
 * fermate consecutive lo scarto mediano e' 14 secondi, il 75esimo percentile
 * 24, il 90esimo 40. Non e' un numero copiato trenta volte — e' una
 * previsione che deriva lungo la corsa, ed e' esattamente cio' che il nostro
 * modello si stava inventando.
 *
 * ## Perche' una sezione nuova e non un record piu' largo
 *
 * `/rt/v1/updates` ha record fissi da 32 byte e il lettore installato sui
 * telefoni fa `require(recordSize == 32)`. Allargarlo vorrebbe dire che ogni
 * versione gia' distribuita smette di vedere i ritardi — in silenzio, perche'
 * l'eccezione viene inghiottita — e il Worker si deploya solo in avanti.
 * Quindi `/rt/v1/updates` resta congelato per sempre e le previsioni vivono
 * a `/rt/v1/predictions`, che le versioni vecchie non chiedono.
 *
 * ## Il formato, e dove sono finiti i byte
 *
 * Record variabili, quindi non si puo' usare lo schema "conta e affetta"
 * delle altre sezioni. Si usa lo stesso trucco del bundle (`PATTERN_STOPS`):
 * un indice a record fissi che punta dentro un blob a record fissi.
 *
 *   header 32 B | INDEX (40 B per corsa) | POINTS (6 B per previsione)
 *
 * La prima versione teneva l'hash a 64 bit della fermata **su ogni punto**.
 * Sono otto byte casuali che gzip non comprime, e a quel prezzo la sezione
 * pesava il 40% in piu'. Adesso l'hash sta due volte per CORSA — la prima e
 * l'ultima fermata dell'elenco — e i punti portano solo lo `stop_sequence`.
 *
 * Non e' un atto di fede sullo `stop_sequence`: e' un'ancora verificabile.
 * L'app sa che la corsa ha N fermate; con i due estremi puo' controllare che
 * `posizione = seq - scarto` porti davvero su quelle due fermate e che la
 * distanza fra i due estremi sia la stessa nelle due numerazioni. Se il
 * controllo passa, la mappatura vale per tutte le fermate in mezzo; se non
 * passa, si ripiega sulla stima invece di attribuire il ritardo alla fermata
 * sbagliata — che e' lo sbaglio gia' costato una fermata di scarto su tutto
 * il tabellone (il feed di at numera da 1, il bundle da 0).
 *
 * Header:
 *    0 u8[4] "FTRT"   4 u16 version = 2   6 u8 kind = 4   7 u8 headerLen = 32
 *    8 u32 generatedAt      12 u32 feedTimestamp
 *   16 u32 tripCount        20 u16 tripRecord = 40   22 u16 flags
 *   24 u32 pointCount       28 u16 pointRecord = 6   30 u16 pad
 *
 * INDEX, ordinato per tripHash crescente (l'app ci fa la ricerca binaria):
 *    0 i64 tripHash (0 = il feed non dichiara la corsa)
 *    8 i64 routeHash
 *   16 u32 startTimeSec (0xFFFFFFFF = ignoto)
 *   20 u32 firstPoint      indice della prima previsione nel blob
 *   24 u16 pointCount
 *   26 u8  status (0 ok, 1 cancellata, 2 aggiunta, 3 senza dati)
 *   27 u8  direction (0xFF = ignota)
 *   28 i16 tripDelaySec    il `delay` complessivo del TripUpdate
 *                          (-32768 = assente; e' diverso da "zero, in orario")
 *   30 u16 pad
 *   32 u32 firstStopId32  i 32 bit bassi di FNV-1a 64 dello stop_id del PRIMO
 *                         punto (0 = assente)
 *   36 u32 lastStopId32   idem per l'ULTIMO punto
 *
 * POINTS:
 *    0 u16 seq        lo stop_sequence dichiarato (0xFFFF = assente)
 *    2 i16 delayDelta la DIFFERENZA dal punto precedente della stessa corsa
 *                     (il primo punto si conta da zero, quindi e' assoluto)
 *    4 u8  rel        0 prevista, 1 SALTATA, 2 senza dati
 *    5 u8  from       0 arrivo, 1 partenza, 0xFF nessuno dei due
 *
 * I ritardi viaggiano come differenze perche' sono numeri quasi casuali e
 * gzip non ne cava niente, mentre gli scarti fra fermate vicine sono piccoli
 * (mediana 14 secondi) e quindi quasi tutti a byte alto zero. Misurato sul
 * feed vero: 74 kB compressi con gli hash a 64 bit su ogni punto e i ritardi
 * assoluti, 64 kB con le ancore a 32 bit e le differenze. L'app li risomma
 * scorrendo i punti di una corsa, che e' l'ordine in cui li legge comunque.
 *
 * Le ancore bastano a 32 bit perche' non servono a CERCARE niente: servono a
 * confermare una mappatura che l'app ha gia' ricavato. Una collisione su
 * quattro miliardi, su due controlli, e' ben oltre il necessario.
 *
 * ## La compattazione
 *
 * Le previsioni identiche di seguito si emettono una volta sola. E' lecito
 * perche' la regola di GTFS-RT dice esattamente questo: una previsione vale
 * per tutte le fermate successive finche' non ce n'e' un'altra. Quindi non e'
 * una compressione con perdita, e' scrivere la stessa cosa senza ripeterla.
 * Sul feed vero toglie pochissimo — i valori non si ripetono mai — ma costa
 * niente e protegge dal giorno in cui l'origine cambiasse abitudine.
 *
 * Non si accorpano invece le previsioni VICINE ma diverse: l'app arrotonda al
 * minuto, e un arrotondamento sopra l'altro sposta di un minuto intero i
 * valori vicini al confine. Su un'app che esiste per far tornare i numeri con
 * quelli ufficiali e' l'errore da non fare.
 *
 * Le fermate SALTATE e quelle senza dati si emettono sempre: non sono ritardi
 * che si propagano, sono fatti di quella fermata.
 */

import { fnv64 } from './snapshot.js';

export const PRED_HEADER_LEN = 32;
export const PRED_TRIP_RECORD = 40;
export const PRED_POINT_RECORD = 6;
export const PRED_VERSION = 2;
export const PRED_KIND = 4;

/** Assente, per un i16: -32768 non e' un ritardo plausibile. */
export const NO_DELAY = -32768;

/**
 * Il tetto ai punti di una sezione, e la valvola che lo fa rispettare.
 *
 * Non e' una soglia tarata a occhio. Misurata sul feed vero nell'ora di punta
 * del 15/09/2026: ~1.250 corse e ~19.000 previsioni, cioe' 160 kB grezzi e 64
 * compressi con questa codifica. Quarantamila punti danno il doppio di
 * margine e tengono il caso peggiore sotto i 120 kB: tanto, ma una volta ogni
 * due minuti e solo a schermata aperta, e comunque meno delle tile che la
 * stessa mappa scarica.
 *
 * La prima stesura aveva il tetto a seimila, tarato su una stima del feed che
 * si e' rivelata sbagliata di tre volte: la valvola scattava sempre e tagliava
 * ogni corsa a quattro previsioni, cioe' buttava proprio il dato che questa
 * sezione esiste per portare. Le stime vanno misurate.
 */
export const MAX_POINTS = 40000;

/** bit 0: la sezione e' stata troncata per stare nel tetto. */
export const FLAG_TRUNCATED = 1;

const REL_SCHEDULED = 0;
const REL_SKIPPED = 1;
const REL_NO_DATA = 2;

/** "HH:MM:SS" GTFS (puo' superare le 24) -> secondi dal giorno di servizio. */
function startTimeSeconds(s) {
  if (!s) return 0xffffffff;
  const m = /^(\d+):(\d\d):(\d\d)$/.exec(s);
  if (!m) return 0xffffffff;
  return Number(m[1]) * 3600 + Number(m[2]) * 60 + Number(m[3]);
}

function clampDelay(d) {
  return Math.max(-32000, Math.min(32000, Math.round(d)));
}

/**
 * Il ritardo di un punto sta in una finestra piu' stretta di quella del
 * ritardo complessivo, e non per capriccio: viaggiando come differenza, due
 * valori agli estremi opposti darebbero uno scarto da 64.000 che in un i16
 * non ci sta — e un traboccamento li' non sposta un punto, sfasa tutta la
 * corsa da quel punto in poi, perche' l'app risomma. Quattro ore e mezzo di
 * ritardo sono gia' un dato senza senso: tagliarle non toglie niente di vero.
 */
function clampPointDelay(d) {
  return Math.max(-16000, Math.min(16000, Math.round(d)));
}

/** GTFS-RT StopTimeUpdate.ScheduleRelationship -> i nostri tre casi. */
function pointRelation(rel) {
  if (rel === 1) return REL_SKIPPED;
  if (rel === 2) return REL_NO_DATA;
  return REL_SCHEDULED; // SCHEDULED, UNSCHEDULED e qualunque cosa nuova
}

/**
 * Le previsioni di una corsa, senza le ripetizioni.
 *
 * Si tiene un punto se e' il primo, se dice qualcosa di diverso dal
 * precedente, o se non e' una previsione normale (saltata / senza dati, che
 * non si propagano). I punti che non portano ne' un ritardo ne' uno stato
 * particolare non dicono niente e non entrano.
 */
export function compressPoints(stops) {
  const out = [];
  let lastDelay = null;
  let lastRel = REL_SCHEDULED;
  for (const s of stops) {
    const rel = pointRelation(s.rel);
    if (rel === REL_SCHEDULED && s.delay === null) continue;
    const changed = out.length === 0 ||
      rel !== REL_SCHEDULED ||
      lastRel !== REL_SCHEDULED ||
      s.delay !== lastDelay;
    if (changed) out.push({ ...s, rel });
    lastDelay = s.delay;
    lastRel = rel;
  }
  return out;
}

/** I 32 bit bassi dell'hash di una fermata: l'ancora, non una chiave. */
function stopId32(point) {
  if (!point || !point.stopId) return 0;
  return Number(BigInt.asUintN(32, fnv64(point.stopId)));
}

/** Lo stato della corsa nel suo insieme. */
function tripStatus(u, points) {
  if (u.canceled) return 1;
  const schedRel = (u.trip && u.trip.schedRel) || 0;
  if (schedRel === 1) return 2; // ADDED
  const hasSomething = points.length > 0 ||
    (u.overallDelay !== null && u.overallDelay !== undefined);
  return hasSomething ? 0 : 3;
}

/**
 * La sezione pronta da servire, piu' i conteggi per /health.
 *
 * I conteggi non sono decorativi: sono quelli che hanno detto che il feed
 * pubblica previsioni davvero diverse fermata per fermata, e non trenta copie
 * dello stesso numero. Sono anche quelli che hanno mostrato il primo tetto
 * tarato male.
 */
export function buildPredictions({ generatedAt, tu, flags = 0 }) {
  const updates = (tu && tu.updates) || [];
  const stats = {
    trips: 0,
    tripsWithPoints: 0,
    tripsOnlyOverall: 0,
    tripsNoData: 0,
    rawPoints: 0,
    points: 0,
    pointsWithoutStopId: 0,
    pointsTimeOnly: 0,
    skipped: 0,
    truncated: false,
    pointsDropped: 0,
  };

  const rows = [];
  for (const u of updates) {
    if (!u.trip) continue;
    const stops = u.stops || [];
    stats.rawPoints += stops.length;
    for (const s of stops) {
      if (s.delay === null && s.time !== null && s.time !== undefined) stats.pointsTimeOnly++;
    }
    const points = compressPoints(stops);
    stats.points += points.length;
    for (const p of points) {
      if (!p.stopId) stats.pointsWithoutStopId++;
      if (p.rel === REL_SKIPPED) stats.skipped++;
    }
    if (points.length > 0) stats.tripsWithPoints++;
    const status = tripStatus(u, points);
    if (status === 3) stats.tripsNoData++;
    if (points.length === 0 && u.overallDelay !== null && u.overallDelay !== undefined) {
      stats.tripsOnlyOverall++;
    }
    rows.push({ u, points, status });
  }
  stats.trips = rows.length;

  // La valvola. Si taglia la CODA di ogni corsa, non le corse intere: la
  // previsione che serve di piu' e' quella delle prossime fermate, e una
  // corsa senza previsioni sparirebbe del tutto dal live invece di averne
  // meno. Il taglio e' uguale per tutti, cosi' nessuna linea e' sfavorita.
  if (stats.points > MAX_POINTS && rows.length > 0) {
    const perTrip = Math.max(2, Math.floor(MAX_POINTS / rows.length));
    let kept = 0;
    for (const row of rows) {
      if (row.points.length > perTrip) {
        stats.pointsDropped += row.points.length - perTrip;
        row.points = row.points.slice(0, perTrip);
      }
      kept += row.points.length;
    }
    stats.points = kept;
    stats.truncated = true;
    flags |= FLAG_TRUNCATED;
  }

  // Ordinate per hash: e' quello che permette all'app la ricerca binaria
  // invece di costruirsi una mappa a ogni giro di poll.
  rows.sort((a, b) => {
    const ha = a.u.trip.tripId ? fnv64(a.u.trip.tripId) : 0n;
    const hb = b.u.trip.tripId ? fnv64(b.u.trip.tripId) : 0n;
    return ha < hb ? -1 : ha > hb ? 1 : 0;
  });

  const total = PRED_HEADER_LEN +
    rows.length * PRED_TRIP_RECORD +
    stats.points * PRED_POINT_RECORD;
  const bytes = new Uint8Array(total);
  const view = new DataView(bytes.buffer);

  bytes[0] = 0x46; bytes[1] = 0x54; bytes[2] = 0x52; bytes[3] = 0x54; // FTRT
  view.setUint16(4, PRED_VERSION, true);
  view.setUint8(6, PRED_KIND);
  view.setUint8(7, PRED_HEADER_LEN);
  view.setUint32(8, generatedAt, true);
  view.setUint32(12, (tu && tu.timestamp) || 0, true);
  view.setUint32(16, rows.length, true);
  view.setUint16(20, PRED_TRIP_RECORD, true);
  view.setUint16(22, (flags >>> 0) & 0xffff, true);
  view.setUint32(24, stats.points, true);
  view.setUint16(28, PRED_POINT_RECORD, true);

  const indexOff = PRED_HEADER_LEN;
  const pointsOff = indexOff + rows.length * PRED_TRIP_RECORD;
  let pointCursor = 0;

  rows.forEach((row, i) => {
    const trip = row.u.trip || {};
    const o = indexOff + i * PRED_TRIP_RECORD;
    view.setBigInt64(o, trip.tripId ? fnv64(trip.tripId) : 0n, true);
    view.setBigInt64(o + 8, trip.routeId ? fnv64(trip.routeId) : 0n, true);
    view.setUint32(o + 16, startTimeSeconds(trip.startTime), true);
    view.setUint32(o + 20, pointCursor, true);
    view.setUint16(o + 24, Math.min(0xffff, row.points.length), true);
    view.setUint8(o + 26, row.status);
    view.setUint8(o + 27, trip.direction === null || trip.direction === undefined ? 0xff : trip.direction);
    const overall = row.u.overallDelay;
    view.setInt16(o + 28, overall === null || overall === undefined ? NO_DELAY : clampDelay(overall), true);

    // Le due ancore. Sono gli unici stop_id che viaggiano, e servono all'app
    // per verificare la mappatura invece di fidarsene.
    const firstPoint = row.points[0];
    const lastPoint = row.points[row.points.length - 1];
    view.setUint32(o + 32, stopId32(firstPoint), true);
    view.setUint32(o + 36, stopId32(lastPoint), true);

    let previousDelay = 0;
    for (const p of row.points) {
      const po = pointsOff + pointCursor * PRED_POINT_RECORD;
      const delay = p.delay === null ? previousDelay : clampPointDelay(p.delay);
      view.setUint16(po, p.seq === null || p.seq === undefined ? 0xffff : Math.min(0xfffe, p.seq), true);
      view.setInt16(po + 2, delay - previousDelay, true);
      view.setUint8(po + 4, p.rel);
      view.setUint8(po + 5, p.from === undefined || p.from < 0 ? 0xff : p.from);
      previousDelay = delay;
      pointCursor++;
    }
  });

  return { bytes, stats };
}

/** Rilegge l'intestazione della sezione: serve ai test e alla diagnostica. */
export function readPredictionsHeader(bytes) {
  if (!bytes || bytes.length < PRED_HEADER_LEN) return null;
  if (bytes[0] !== 0x46 || bytes[1] !== 0x54 || bytes[2] !== 0x52 || bytes[3] !== 0x54) return null;
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  return {
    version: view.getUint16(4, true),
    kind: view.getUint8(6),
    headerLen: view.getUint8(7),
    generatedAt: view.getUint32(8, true),
    feedTimestamp: view.getUint32(12, true),
    tripCount: view.getUint32(16, true),
    tripRecord: view.getUint16(20, true),
    flags: view.getUint16(22, true),
    pointCount: view.getUint32(24, true),
    pointRecord: view.getUint16(28, true),
  };
}
