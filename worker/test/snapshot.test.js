import assert from 'node:assert/strict';
import { test, describe } from 'node:test';
import { parseFeed } from '../src/gtfsrt.js';
import {
  buildSnapshot, fnv64, readHeader, sliceSection,
  HEADER_LEN, VEHICLE_RECORD, DELAY_RECORD,
} from '../src/snapshot.js';
import { feed, tripUpdateEntity, vehicleEntity } from './proto.js';

/**
 * Il formato binario dello snapshot.
 *
 * Qui il Worker e l'app devono essere d'accordo byte per byte, e il lettore
 * dall'altra parte (`app/.../RtSnapshot.kt`, con il suo RtCodecTest) legge
 * proprio questi offset. I due test insieme sono il contratto: se uno dei
 * due file cambia da solo, uno dei due cade.
 */

const NOW = 1_700_000_000;

function snapshotOf({ vehicles = [], updates = [], alerts = null, flags = 0 }) {
  const vp = parseFeed(feed({ timestamp: NOW - 40, entities: vehicles }), 'vehicles');
  const tu = parseFeed(feed({ timestamp: NOW - 25, entities: updates }), 'updates');
  return buildSnapshot({
    generatedAt: NOW,
    vp,
    tu,
    alertsBytes: alerts,
    alTimestamp: 0,
    flags,
  });
}

describe('hash degli identificatori', () => {
  test('fnv64 da i valori attesi, per sempre', () => {
    // Questi numeri sono il ponte fra due linguaggi: lo stesso elenco e'
    // inchiodato in `core-routing/.../HashCompatTest.kt` contro
    // `Ftb.hash64`. Se una delle due implementazioni cambia, i trip_id del
    // feed smettono di agganciare il bundle — e il sintomo sarebbe "nessun
    // bus ha una linea", non "l'hash e' cambiato".
    assert.equal(fnv64('').toString(), '-3750763034362895579');
    assert.equal(fnv64('a').toString(), '-5808556873153909620');
    assert.equal(fnv64('stopA').toString(), '4968882906940724300');
    assert.equal(fnv64('R1-morning').toString(), '-2162172861602763347');
    assert.equal(fnv64('Autolinee Toscane').toString(), '-2081605873580141004');
  });

  test('fnv64 lavora sui byte UTF-8, non sui caratteri', () => {
    // Le fermate toscane hanno gli accenti. Se i due lati contassero in
    // modo diverso, a sbagliare sarebbero solo quelle: un guasto a macchie.
    assert.equal(fnv64('città').toString(), '-495594276946382062');
    assert.notEqual(fnv64('città').toString(), fnv64('citta').toString());
  });
});

describe('intestazione dello snapshot', () => {
  test('uno snapshot vuoto ha comunque un header leggibile', () => {
    const bytes = snapshotOf({});
    const h = readHeader(bytes);

    assert.equal(bytes.length, HEADER_LEN);
    assert.equal(h.version, 1);
    assert.equal(h.generatedAt, NOW);
    assert.equal(h.vehicleCount, 0);
    assert.equal(h.delayCount, 0);
  });

  test('le tre sezioni si susseguono senza buchi ne sovrapposizioni', () => {
    const bytes = snapshotOf({
      vehicles: [vehicleEntity({ tripId: 'v1' }), vehicleEntity({ tripId: 'v2' })],
      updates: [tripUpdateEntity({ tripId: 'u1', stops: [{ seq: 1, departure: 60 }] })],
      alerts: new Uint8Array([1, 2, 3, 4, 5]),
    });
    const h = readHeader(bytes);

    assert.equal(h.vehiclesOff, HEADER_LEN);
    assert.equal(h.vehiclesLen, 2 * VEHICLE_RECORD);
    assert.equal(h.delaysOff, h.vehiclesOff + h.vehiclesLen);
    assert.equal(h.delaysLen, 1 * DELAY_RECORD);
    assert.equal(h.alertsOff, h.delaysOff + h.delaysLen);
    assert.equal(h.alertsLen, 5);
    assert.equal(bytes.length, h.alertsOff + h.alertsLen);
  });

  test('i timestamp dei tre feed restano distinti', () => {
    // Sono tre origini che si rigenerano per conto loro: confonderli vuol
    // dire dichiarare fresco un feed vecchio perche' un altro e' arrivato.
    const h = readHeader(snapshotOf({}));

    assert.equal(h.vpTimestamp, NOW - 40);
    assert.equal(h.tuTimestamp, NOW - 25);
  });

  test('readHeader rifiuta i byte che non sono uno snapshot', () => {
    assert.equal(readHeader(null), null);
    assert.equal(readHeader(new Uint8Array(10)), null);
    assert.equal(readHeader(new Uint8Array(HEADER_LEN)), null);
  });
});

describe('record dei veicoli', () => {
  test('un veicolo completo si scrive agli offset giusti', () => {
    const bytes = snapshotOf({
      vehicles: [vehicleEntity({
        tripId: 'corsa-1',
        routeId: 'linea-7',
        startTime: '07:30:00',
        direction: 1,
        lat: 43.7696,
        lon: 11.2558,
        bearing: 275,
        speed: 8.3,
        timestamp: NOW - 42,
        vehicleId: 'bus-42',
      })],
    });
    const h = readHeader(bytes);
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    const o = h.vehiclesOff;

    assert.equal(view.getBigInt64(o, true), fnv64('corsa-1'));
    assert.equal(view.getBigInt64(o + 8, true), fnv64('linea-7'));
    assert.equal(view.getInt32(o + 16, true), 43769600);
    assert.equal(view.getInt32(o + 20, true), 11255800);
    assert.equal(view.getUint16(o + 24, true), 275);
    assert.equal(view.getUint16(o + 26, true), 42, 'eta del fix = generatedAt - timestamp');
    assert.equal(view.getUint32(o + 28, true), 7 * 3600 + 30 * 60);
    assert.equal(view.getUint8(o + 32), 1);
    assert.equal(view.getUint16(o + 34, true), 83, 'velocita in decimetri al secondo');
    assert.notEqual(view.getUint32(o + 36, true), 0);
  });

  test('i campi assenti diventano i sentinella, non zero', () => {
    const bytes = snapshotOf({ vehicles: [vehicleEntity({ lat: 43.0, lon: 11.0 })] });
    const h = readHeader(bytes);
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    const o = h.vehiclesOff;

    assert.equal(view.getBigInt64(o, true), 0n, 'nessuna corsa dichiarata');
    assert.equal(view.getUint16(o + 24, true), 0xffff, 'rotta ignota');
    assert.equal(view.getUint16(o + 26, true), 0xffff, 'eta del fix ignota');
    assert.equal(view.getUint32(o + 28, true), 0xffffffff, 'partenza ignota');
    assert.equal(view.getUint8(o + 32), 0xff, 'direzione ignota');
    assert.equal(view.getUint16(o + 34, true), 0xffff, 'velocita ignota');
    assert.equal(view.getUint32(o + 36, true), 0, 'nessun id mezzo');
  });

  test('start_time oltre le 24 non si tronca', () => {
    const bytes = snapshotOf({
      vehicles: [vehicleEntity({ tripId: 'notturna', startTime: '25:30:00' })],
    });
    const h = readHeader(bytes);
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);

    assert.equal(view.getUint32(h.vehiclesOff + 28, true), 25 * 3600 + 30 * 60);
  });

  test('un start_time che non e un orario diventa il sentinella', () => {
    const bytes = snapshotOf({
      vehicles: [vehicleEntity({ tripId: 'x', startTime: 'boh' })],
    });
    const h = readHeader(bytes);
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);

    assert.equal(view.getUint32(h.vehiclesOff + 28, true), 0xffffffff);
  });

  test('una rotta fuori scala si riporta dentro il giro', () => {
    const bytes = snapshotOf({
      vehicles: [vehicleEntity({ tripId: 'x', bearing: 450 })],
    });
    const h = readHeader(bytes);
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);

    assert.equal(view.getUint16(h.vehiclesOff + 24, true), 90);
  });

  test('un fix nel futuro non diventa un eta negativa', () => {
    // L'orologio dell'origine e il nostro non sono lo stesso orologio.
    const bytes = snapshotOf({
      vehicles: [vehicleEntity({ tripId: 'x', timestamp: NOW + 30 })],
    });
    const h = readHeader(bytes);
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);

    assert.equal(view.getUint16(h.vehiclesOff + 26, true), 0);
  });
});

describe('record dei ritardi', () => {
  test('un ritardo completo si scrive agli offset giusti', () => {
    const bytes = snapshotOf({
      updates: [tripUpdateEntity({
        tripId: 'c1',
        routeId: 'r1',
        startTime: '08:30:00',
        direction: 0,
        stops: [{ seq: 14, departure: 420 }],
      })],
    });
    const h = readHeader(bytes);
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    const o = h.delaysOff;

    assert.equal(view.getBigInt64(o, true), fnv64('c1'));
    assert.equal(view.getBigInt64(o + 8, true), fnv64('r1'));
    assert.equal(view.getUint32(o + 16, true), 8 * 3600 + 30 * 60);
    assert.equal(view.getInt16(o + 20, true), 420);
    assert.equal(view.getUint8(o + 22), 0);
    assert.equal(view.getUint8(o + 23), 0);
    assert.equal(view.getUint16(o + 24, true), 14);
  });

  test('un anticipo arriva negativo fino ai byte', () => {
    const bytes = snapshotOf({
      updates: [tripUpdateEntity({ tripId: 'c1', stops: [{ seq: 2, departure: -180 }] })],
    });
    const h = readHeader(bytes);
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);

    assert.equal(view.getInt16(h.delaysOff + 20, true), -180);
  });

  test('un ritardo assurdo si satura invece di traboccare', () => {
    // i16 arriva a 32767: undici ore di ritardo sono un dato sbagliato, ma
    // il troncamento silenzioso lo trasformerebbe in un anticipo.
    const bytes = snapshotOf({
      updates: [tripUpdateEntity({ tripId: 'c1', stops: [{ seq: 1, departure: 99999 }] })],
    });
    const h = readHeader(bytes);
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);

    assert.equal(view.getInt16(h.delaysOff + 20, true), 32000);
  });

  test('senza ritardo lo stato e senza dati, e il numero resta zero', () => {
    const bytes = snapshotOf({
      updates: [tripUpdateEntity({ tripId: 'c1', stops: [{ seq: 1 }] })],
    });
    const h = readHeader(bytes);
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);

    assert.equal(view.getUint8(h.delaysOff + 22), 3, 'stato: senza dati');
    assert.equal(view.getInt16(h.delaysOff + 20, true), 0);
  });

  test('una corsa cancellata ha il suo stato', () => {
    const bytes = snapshotOf({
      updates: [tripUpdateEntity({ tripId: 'c1', schedRel: 3 })],
    });
    const h = readHeader(bytes);
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);

    assert.equal(view.getUint8(h.delaysOff + 22), 1);
  });

  test('la fermata successiva ignota e il sentinella', () => {
    const bytes = snapshotOf({
      updates: [tripUpdateEntity({ tripId: 'c1', overallDelay: 60 })],
    });
    const h = readHeader(bytes);
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);

    assert.equal(view.getUint16(h.delaysOff + 24, true), 0xffff);
  });
});

describe('affettamento per gli endpoint', () => {
  test('la sezione veicoli porta con se quello che serve a leggerla', () => {
    const bytes = snapshotOf({
      vehicles: [vehicleEntity({ tripId: 'v1' }), vehicleEntity({ tripId: 'v2' })],
      updates: [tripUpdateEntity({ tripId: 'u1', stops: [{ seq: 1, departure: 30 }] })],
    });
    const h = readHeader(bytes);
    const out = sliceSection(bytes, h, 1);
    const view = new DataView(out.buffer, out.byteOffset, out.byteLength);

    assert.deepEqual(Array.from(out.subarray(0, 4)), [0x46, 0x54, 0x52, 0x54]);
    assert.equal(view.getUint16(4, true), 1, 'versione');
    assert.equal(view.getUint8(6), 1, 'kind: veicoli');
    assert.equal(view.getUint32(8, true), NOW);
    assert.equal(view.getUint32(12, true), NOW - 40, 'il timestamp DEL SUO feed');
    assert.equal(view.getUint32(16, true), 2);
    assert.equal(view.getUint16(20, true), VEHICLE_RECORD);
    assert.equal(out.length, 24 + 2 * VEHICLE_RECORD);
  });

  test('la sezione ritardi porta il timestamp dei trip-update, non degli altri', () => {
    const bytes = snapshotOf({
      vehicles: [vehicleEntity({ tripId: 'v1' })],
      updates: [tripUpdateEntity({ tripId: 'u1', stops: [{ seq: 1, departure: 30 }] })],
    });
    const out = sliceSection(bytes, readHeader(bytes), 2);
    const view = new DataView(out.buffer, out.byteOffset, out.byteLength);

    assert.equal(view.getUint8(6), 2);
    assert.equal(view.getUint32(12, true), NOW - 25);
    assert.equal(view.getUint16(20, true), DELAY_RECORD);
    assert.equal(out.length, 24 + DELAY_RECORD);
  });

  test('i byte affettati sono gli stessi dello snapshot', () => {
    const bytes = snapshotOf({
      vehicles: [vehicleEntity({ tripId: 'v1', lat: 43.11, lon: 11.22 })],
    });
    const h = readHeader(bytes);
    const out = sliceSection(bytes, h, 1);

    assert.deepEqual(
      Array.from(out.subarray(24)),
      Array.from(bytes.subarray(h.vehiclesOff, h.vehiclesOff + h.vehiclesLen)),
    );
  });

  test('una sezione vuota resta una risposta valida', () => {
    const bytes = snapshotOf({});
    const out = sliceSection(bytes, readHeader(bytes), 2);
    const view = new DataView(out.buffer, out.byteOffset, out.byteLength);

    assert.equal(out.length, 24);
    assert.equal(view.getUint32(16, true), 0);
  });
});
