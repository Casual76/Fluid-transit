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
 * ## Perche' una sezione nuova e non un record piu' largo
 *
 * `/rt/v1/updates` ha record fissi da 32 byte e il lettore installato sui
 * telefoni fa `require(recordSize == 32)`. Allargarlo vorrebbe dire che ogni
 * versione gia' distribuita smette di vedere i ritardi — in silenzio, perche'
 * l'eccezione viene inghiottita — e il Worker si deploya solo in avanti.
 * Quindi `/rt/v1/updates` resta congelato per sempre e le previsioni vivono
 * a `/rt/v1/predictions`, che le versioni vecchie non chiedono.
 *
 * ## Il formato
 *
 * Record variabili, quindi non si puo' usare lo schema "conta e affetta"
 * delle altre sezioni. Si usa lo stesso trucco del bundle (`PATTERN_STOPS`):
 * un indice a record fissi che punta dentro un blob a record fissi.
 *
 *   header 32 B | INDEX (32 B per corsa) | POINTS (16 B per previsione)
 *
 * Header:
 *    0 u8[4] "FTRT"   4 u16 version = 2   6 u8 kind = 4   7 u8 headerLen = 32
 *    8 u32 generatedAt      12 u32 feedTimestamp
 *   16 u32 tripCount        20 u16 tripRecord = 32   22 u16 flags
 *   24 u32 pointCount       28 u16 pointRecord = 16  30 u16 pad
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
 *
 * POINTS:
 *    0 i64 stopIdHash   FNV-1a 64 di stop_id (0 = il feed non lo dichiara)
 *    8 u16 stopSeq      lo stop_sequence dichiarato (0xFFFF = assente)
 *   10 i16 delaySec     saturato a +/-32000
 *   12 u8  rel          0 prevista, 1 SALTATA, 2 senza dati
 *   13 u8  from         0 arrivo, 1 partenza, 0xFF nessuno dei due
 *   14 u16 pad
 *
 * Lo `stopIdHash` c'e' perche' `stop_sequence` non basta: GTFS lo lascia
 * libero, il bundle non lo conserva, e il feed di at parte da 1 mentre il
 * bundle indicizza da 0 — una trappola che e' gia' costata una fermata di
 * scarto su tutto il tabellone. L'hash del `stop_id` invece e' la stessa
 * chiave che il bundle usa gia'.
 *
 * ## La compattazione
 *
 * Le previsioni si emettono solo quando cambiano. E' lecito perche' la regola
 * di GTFS-RT dice esattamente questo: una previsione vale per tutte le
 * fermate successive finche' non ce n'e' un'altra. Quindi non e' una
 * compressione con perdita, e' scrivere la stessa cosa senza ripeterla.
 * Le fermate SALTATE e quelle senza dati si emettono sempre: non sono
 * ritardi che si propagano, sono fatti di quella fermata.
 */

import { fnv64 } from './snapshot.js';

export const PRED_HEADER_LEN = 32;
export const PRED_TRIP_RECORD = 32;
export const PRED_POINT_RECORD = 16;
export const PRED_VERSION = 2;
export const PRED_KIND = 4;

/** Assente, per un i16: -32768 non e' un ritardo plausibile. */
export const NO_DELAY = -32768;

/**
 * Il tetto ai punti di una sezione, e la valvola che lo fa rispettare.
 *
 * Misurato su un feed sintetico delle dimensioni di quello vero (434 corse,
 * 30 fermate l'una): se l'origine ripete lo stesso ritardo per tutta la corsa
 * la sezione sta in 10 kB compressi, se cambia ogni cinque fermate in 29 kB,
 * ma se mandasse un valore diverso a ogni fermata arriverebbe a 145 kB — che
 * su rete mobile, ogni due minuti, non e' una cosa da fare a qualcuno.
 *
 * Quale dei tre casi sia quello vero non lo sappiamo ancora: lo diranno i
 * conteggi. Nel frattempo il tetto garantisce che il caso peggiore non possa
 * far danno, e quando scatta lo si dichiara (bit 0 di `flags`) invece di
 * consegnare in silenzio una verita' parziale.
 *
 * Non si quantizzano i ritardi per farli collassare: l'app arrotonda al
 * minuto, e un arrotondamento sopra l'altro sposta di un minuto intero i
 * valori vicini al confine. Su un'app che esiste per far tornare i numeri con
 * quelli ufficiali, e' esattamente l'errore da non fare.
 */
export const MAX_POINTS = 6000;

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
 * I conteggi non sono decorativi: dicono se questo cambio serve a qualcosa.
 * Se `points` e `rawPoints` fossero quasi uguali, il feed manderebbe
 * previsioni davvero diverse fermata per fermata; se `points` fosse una
 * frazione minima, starebbe ripetendo lo stesso numero trenta volte e allora
 * il guadagno e' di onesta' (diciamo "dichiarato" invece di "stimato") piu'
 * che di precisione. Sono due conclusioni diverse e vanno misurate, non
 * indovinate.
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
  stats.truncated = false;
  stats.pointsDropped = 0;

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
  view.setUint16(22, flags >>> 0 & 0xffff, true);
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

    for (const p of row.points) {
      const po = pointsOff + pointCursor * PRED_POINT_RECORD;
      view.setBigInt64(po, p.stopId ? fnv64(p.stopId) : 0n, true);
      view.setUint16(po + 8, p.seq === null || p.seq === undefined ? 0xffff : Math.min(0xfffe, p.seq), true);
      view.setInt16(po + 10, p.delay === null ? 0 : clampDelay(p.delay), true);
      view.setUint8(po + 12, p.rel);
      view.setUint8(po + 13, p.from === undefined || p.from < 0 ? 0xff : p.from);
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
