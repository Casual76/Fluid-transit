/**
 * Il formato binario dello snapshot realtime: quello che il cron scrive su
 * R2 (`rt/latest.bin`) e quello che gli endpoint affettano per l'app.
 *
 * L'app NON riceve GTFS-RT e non riceve JSON: riceve record fissi
 * little-endian gia' pronti da leggere con un ByteBuffer. Gli identificatori
 * del feed viaggiano come hash FNV-1a a 64 bit — la stessa funzione del
 * bundle (`Ftb.hash64`), cosi' `TRIP_ID_INDEX` e `routeIdHash` risolvono i
 * record senza portarsi dietro le stringhe.
 *
 * Layout di rt/latest.bin:
 *   header 64 B | VEHICLES (40 B/record) | DELAYS (32 B/record) | ALERTS (pb grezzo)
 *
 * Header (offset, tipo, campo):
 *    0 u8[4]  magic "FTRT"
 *    4 u16    version = 1
 *    6 u16    headerLen = 64
 *    8 u32    generatedAt (epoch s)
 *   12 u32    vpTimestamp   16 u32 tuTimestamp   20 u32 alTimestamp
 *   24 u32    vehicleCount  28 u32 vehiclesOff   32 u32 vehiclesLen
 *   36 u32    delayCount    40 u32 delaysOff     44 u32 delaysLen
 *   48 u32    alertsOff     52 u32 alertsLen
 *   56 u32    flags (bit0 vp mancante, bit1 tu mancante, bit2 alerts mancante)
 *   60 u32    riservato
 *
 * Record VEHICLES (40 B):
 *    0 i64 tripHash (0 = assente)   8 i64 routeHash (0 = assente)
 *   16 i32 lat*1e6                 20 i32 lon*1e6
 *   24 u16 bearing gradi (0xFFFF = ignoto)
 *   26 u16 eta' del fix in s al momento della generazione (0xFFFF = ignota)
 *   28 u32 startTimeSec dal giorno di servizio (0xFFFFFFFF = ignoto)
 *   32 u8  direction (0xFF = ignota)  33 u8 schedRel
 *   34 u16 velocita' dm/s (0xFFFF = ignota)
 *   36 u32 chiave veicolo FNV-1a 32 (per l'interpolazione, 0 = ignota)
 *
 * Record DELAYS (32 B):
 *    0 i64 tripHash   8 i64 routeHash
 *   16 u32 startTimeSec (0xFFFFFFFF = ignoto)
 *   20 i16 delaySec (saturato a +/-32000)
 *   22 u8  status (0 ok, 1 cancellata, 2 aggiunta, 3 senza dati)
 *   23 u8  direction (0xFF = ignota)
 *   24 u16 prossima stop_sequence (0xFFFF = ignota)
 *   26 u16 pad   28 u32 pad
 */

export const SNAPSHOT_KEY = 'rt/latest.bin';
export const HEADER_LEN = 64;
export const VEHICLE_RECORD = 40;
export const DELAY_RECORD = 32;

// --- FNV-1a, identica al bundle --------------------------------------------

const FNV64_OFFSET = 0xcbf29ce484222325n;
const FNV64_PRIME = 0x100000001b3n;
const MASK64 = 0xffffffffffffffffn;
const utf8enc = new TextEncoder();

/** FNV-1a 64 bit sugli stessi byte UTF-8 di `Ftb.hash64`: DEVONO coincidere. */
export function fnv64(s) {
  let h = FNV64_OFFSET;
  const bytes = utf8enc.encode(s);
  for (let i = 0; i < bytes.length; i++) {
    h ^= BigInt(bytes[i]);
    h = (h * FNV64_PRIME) & MASK64;
  }
  return BigInt.asIntN(64, h);
}

/** FNV-1a 32 bit: chiave leggera del veicolo, serve solo alla continuita' visiva. */
function fnv32(s) {
  let h = 0x811c9dc5;
  const bytes = utf8enc.encode(s);
  for (let i = 0; i < bytes.length; i++) {
    h ^= bytes[i];
    h = Math.imul(h, 0x01000193) >>> 0;
  }
  return h >>> 0;
}

/** "HH:MM:SS" GTFS (puo' superare le 24) -> secondi dal giorno di servizio. */
function startTimeSeconds(s) {
  if (!s) return 0xffffffff;
  const m = /^(\d+):(\d\d):(\d\d)$/.exec(s);
  if (!m) return 0xffffffff;
  return Number(m[1]) * 3600 + Number(m[2]) * 60 + Number(m[3]);
}

// --- costruzione -----------------------------------------------------------

export function buildSnapshot({ generatedAt, vp, tu, alertsBytes, flags }) {
  const vehicles = vp ? vp.vehicles : [];
  const updates = tu ? tu.updates : [];
  const alerts = alertsBytes || new Uint8Array(0);

  const vehiclesLen = vehicles.length * VEHICLE_RECORD;
  const delaysLen = updates.length * DELAY_RECORD;
  const total = HEADER_LEN + vehiclesLen + delaysLen + alerts.length;
  const buf = new ArrayBuffer(total);
  const view = new DataView(buf);
  const bytes = new Uint8Array(buf);

  bytes[0] = 0x46; bytes[1] = 0x54; bytes[2] = 0x52; bytes[3] = 0x54; // FTRT
  view.setUint16(4, 1, true);
  view.setUint16(6, HEADER_LEN, true);
  view.setUint32(8, generatedAt, true);
  view.setUint32(12, (vp && vp.timestamp) || 0, true);
  view.setUint32(16, (tu && tu.timestamp) || 0, true);
  view.setUint32(20, alertsTimestamp(alerts) || 0, true);
  const vehiclesOff = HEADER_LEN;
  const delaysOff = vehiclesOff + vehiclesLen;
  const alertsOff = delaysOff + delaysLen;
  view.setUint32(24, vehicles.length, true);
  view.setUint32(28, vehiclesOff, true);
  view.setUint32(32, vehiclesLen, true);
  view.setUint32(36, updates.length, true);
  view.setUint32(40, delaysOff, true);
  view.setUint32(44, delaysLen, true);
  view.setUint32(48, alertsOff, true);
  view.setUint32(52, alerts.length, true);
  view.setUint32(56, flags >>> 0, true);

  let o = vehiclesOff;
  for (const v of vehicles) {
    const trip = v.trip || {};
    view.setBigInt64(o, trip.tripId ? fnv64(trip.tripId) : 0n, true);
    view.setBigInt64(o + 8, trip.routeId ? fnv64(trip.routeId) : 0n, true);
    view.setInt32(o + 16, Math.round(v.lat * 1e6), true);
    view.setInt32(o + 20, Math.round(v.lon * 1e6), true);
    view.setUint16(o + 24, v.bearing === null ? 0xffff : (Math.round(v.bearing) % 360 + 360) % 360, true);
    const age = v.timestamp ? Math.max(0, Math.min(0xfffe, generatedAt - v.timestamp)) : 0xffff;
    view.setUint16(o + 26, age, true);
    view.setUint32(o + 28, startTimeSeconds(trip.startTime), true);
    view.setUint8(o + 32, trip.direction === null || trip.direction === undefined ? 0xff : trip.direction);
    view.setUint8(o + 33, trip.schedRel || 0);
    view.setUint16(o + 34, v.speed === null ? 0xffff : Math.max(0, Math.min(0xfffe, Math.round(v.speed * 10))), true);
    view.setUint32(o + 36, v.vehicleId ? fnv32(v.vehicleId) : 0, true);
    o += VEHICLE_RECORD;
  }

  for (const u of updates) {
    const trip = u.trip || {};
    view.setBigInt64(o, trip.tripId ? fnv64(trip.tripId) : 0n, true);
    view.setBigInt64(o + 8, trip.routeId ? fnv64(trip.routeId) : 0n, true);
    view.setUint32(o + 16, startTimeSeconds(trip.startTime), true);
    const d = u.delay === null ? 0 : Math.max(-32000, Math.min(32000, u.delay));
    view.setInt16(o + 20, d, true);
    view.setUint8(o + 22, u.canceled ? 1 : (u.delay === null ? 3 : 0));
    view.setUint8(o + 23, trip.direction === null || trip.direction === undefined ? 0xff : trip.direction);
    view.setUint16(o + 24, u.nextStopSeq === null ? 0xffff : Math.min(0xfffe, u.nextStopSeq), true);
    o += DELAY_RECORD;
  }

  bytes.set(alerts, alertsOff);
  return bytes;
}

// L'header degli alerts serve solo per il confronto "e' cambiato?": si
// estrae pigramente il timestamp senza tenere una copia parsata.
import { parseFeed } from './gtfsrt.js';

function alertsTimestamp(alertsBytes) {
  if (!alertsBytes || alertsBytes.length === 0) return 0;
  try {
    return parseFeed(alertsBytes, 'header').timestamp || 0;
  } catch {
    return 0;
  }
}

/** Legge i tre timestamp dall'header di uno snapshot esistente. */
export function readHeader(bytes) {
  if (!bytes || bytes.length < HEADER_LEN) return null;
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  if (bytes[0] !== 0x46 || bytes[1] !== 0x54 || bytes[2] !== 0x52 || bytes[3] !== 0x54) return null;
  return {
    version: view.getUint16(4, true),
    generatedAt: view.getUint32(8, true),
    vpTimestamp: view.getUint32(12, true),
    tuTimestamp: view.getUint32(16, true),
    alTimestamp: view.getUint32(20, true),
    vehicleCount: view.getUint32(24, true),
    vehiclesOff: view.getUint32(28, true),
    vehiclesLen: view.getUint32(32, true),
    delayCount: view.getUint32(36, true),
    delaysOff: view.getUint32(40, true),
    delaysLen: view.getUint32(44, true),
    alertsOff: view.getUint32(48, true),
    alertsLen: view.getUint32(52, true),
    flags: view.getUint32(56, true),
  };
}

/**
 * La risposta binaria di un endpoint: mini-header di 24 B + record copiati
 * dallo snapshot. L'app riconosce cosa sta leggendo dal campo `kind`.
 *
 *   0 u8[4] "FTRT"   4 u16 version   6 u8 kind (1 vehicles, 2 delays)
 *   7 u8 pad   8 u32 generatedAt   12 u32 feedTimestamp
 *  16 u32 count   20 u16 recordSize   22 u16 flags
 */
export function sliceSection(snapshot, header, kind) {
  const isVehicles = kind === 1;
  const off = isVehicles ? header.vehiclesOff : header.delaysOff;
  const len = isVehicles ? header.vehiclesLen : header.delaysLen;
  const count = isVehicles ? header.vehicleCount : header.delayCount;
  const feedTs = isVehicles ? header.vpTimestamp : header.tuTimestamp;
  const recordSize = isVehicles ? VEHICLE_RECORD : DELAY_RECORD;

  const out = new Uint8Array(24 + len);
  const view = new DataView(out.buffer);
  out[0] = 0x46; out[1] = 0x54; out[2] = 0x52; out[3] = 0x54;
  view.setUint16(4, 1, true);
  view.setUint8(6, kind);
  view.setUint32(8, header.generatedAt, true);
  view.setUint32(12, feedTs, true);
  view.setUint32(16, count, true);
  view.setUint16(20, recordSize, true);
  view.setUint16(22, header.flags & 0xffff, true);
  out.set(snapshot.subarray(off, off + len), 24);
  return out;
}
