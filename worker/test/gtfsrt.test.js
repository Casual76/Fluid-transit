import assert from 'node:assert/strict';
import { test, describe } from 'node:test';
import { parseFeed } from '../src/gtfsrt.js';
import { feed, proto, tripUpdateEntity, vehicleEntity } from './proto.js';

/**
 * Il decoder GTFS-RT del Worker.
 *
 * E' la prima porta di tutta la catena realtime: quello che si perde qui non
 * lo recupera piu' nessuno a valle, e il sintomo arriva lontanissimo dalla
 * causa (un bus disegnato dove non e', un ritardo che non compare). Finora
 * non aveva un solo controllo.
 */

describe('intestazione', () => {
  test('versione e timestamp si leggono senza toccare le entita', () => {
    const bytes = feed({
      version: '1.0',
      timestamp: 1_699_999_940,
      entities: [vehicleEntity({ tripId: 't1' })],
    });

    const out = parseFeed(bytes, 'header');

    assert.equal(out.version, '1.0');
    assert.equal(out.timestamp, 1_699_999_940);
    // 'header' salta le entita': e' il giro che il cron fa ogni minuto per
    // decidere se c'e' qualcosa di nuovo, e deve costare quasi zero.
    assert.equal(out.vehicles.length, 0);
    assert.equal(out.updates.length, 0);
  });

  test('un feed senza intestazione non fa saltare il parse', () => {
    const out = parseFeed(feed({ entities: [] }), 'header');
    assert.equal(typeof out.timestamp, 'number');
  });
});

describe('veicoli', () => {
  test('un veicolo completo arriva intero', () => {
    const bytes = feed({
      entities: [vehicleEntity({
        tripId: 'corsa-1',
        routeId: 'linea-7',
        startTime: '07:30:00',
        direction: 1,
        lat: 43.7696,
        lon: 11.2558,
        bearing: 275.4,
        speed: 8.25,
        timestamp: 1_699_999_900,
        vehicleId: 'bus-42',
      })],
    });

    const v = parseFeed(bytes, 'vehicles').vehicles[0];

    assert.equal(v.trip.tripId, 'corsa-1');
    assert.equal(v.trip.routeId, 'linea-7');
    assert.equal(v.trip.startTime, '07:30:00');
    assert.equal(v.trip.direction, 1);
    assert.ok(Math.abs(v.lat - 43.7696) < 1e-4);
    assert.ok(Math.abs(v.lon - 11.2558) < 1e-4);
    assert.ok(Math.abs(v.bearing - 275.4) < 1e-3);
    assert.ok(Math.abs(v.speed - 8.25) < 1e-6);
    assert.equal(v.timestamp, 1_699_999_900);
    assert.equal(v.vehicleId, 'bus-42');
  });

  test('un veicolo senza posizione si butta', () => {
    // Il feed ne manda: sono mezzi censiti ma senza fix. Disegnarli a
    // (0,0) avrebbe messo autobus toscani nel golfo di Guinea.
    const bytes = feed({
      entities: [
        vehicleEntity({ tripId: 'senza-fix', lat: null, lon: null }),
        vehicleEntity({ tripId: 'con-fix' }),
      ],
    });

    const vehicles = parseFeed(bytes, 'vehicles').vehicles;

    assert.equal(vehicles.length, 1);
    assert.equal(vehicles[0].trip.tripId, 'con-fix');
  });

  test('i campi assenti restano null, che e diverso da zero', () => {
    const bytes = feed({ entities: [vehicleEntity({ tripId: 't' })] });
    const v = parseFeed(bytes, 'vehicles').vehicles[0];

    assert.equal(v.bearing, null);
    assert.equal(v.speed, null);
    assert.equal(v.timestamp, null);
    assert.equal(v.vehicleId, null);
    assert.equal(v.trip.direction, null);
  });

  test('senza vehicle.id si ripiega sull etichetta', () => {
    const bytes = feed({
      entities: [(entity) => entity.message(4, (v) => {
        v.message(2, (p) => { p.float(1, 43.0); p.float(2, 11.0); });
        v.message(8, (d) => d.string(2, 'etichetta-9'));
      })],
    });

    assert.equal(parseFeed(bytes, 'vehicles').vehicles[0].vehicleId, 'etichetta-9');
  });

  test('i campi che non conosciamo si saltano senza danni', () => {
    // La compatibilita in avanti non e teorica: il feed e' 1.0 per i veicoli
    // e 2.0 per gli avvisi, e l'origine puo' aggiungere campi quando vuole.
    const bytes = feed({
      entities: [(entity) => entity.message(4, (v) => {
        v.varint(99, 1234);
        v.string(98, 'roba nuova');
        v.fixed64(97, 7n);
        v.message(2, (p) => { p.float(1, 43.5); p.float(2, 11.5); });
        v.message(96, (m) => m.string(1, 'annidata'));
        v.varint(5, 1_700_000_000);
      })],
    });

    const v = parseFeed(bytes, 'vehicles').vehicles[0];

    assert.ok(Math.abs(v.lat - 43.5) < 1e-4);
    assert.equal(v.timestamp, 1_700_000_000);
  });

  test('con want vehicles i trip-update non si decodificano', () => {
    const bytes = feed({
      entities: [
        tripUpdateEntity({ tripId: 'u1', stops: [{ seq: 3, departure: 60 }] }),
        vehicleEntity({ tripId: 'v1' }),
      ],
    });

    const out = parseFeed(bytes, 'vehicles');

    assert.equal(out.vehicles.length, 1);
    assert.equal(out.updates.length, 0);
  });
});

describe('ritardi', () => {
  test('il primo StopTimeUpdate con un ritardo e quello che vale', () => {
    // Gli update partono dalla prossima fermata, quindi il primo e' "il
    // ritardo adesso". Gli altri descrivono il futuro della corsa.
    const bytes = feed({
      entities: [tripUpdateEntity({
        tripId: 'c1',
        routeId: 'r1',
        stops: [
          { seq: 12, departure: 240 },
          { seq: 13, departure: 180 },
          { seq: 14, departure: 60 },
        ],
      })],
    });

    const u = parseFeed(bytes, 'updates').updates[0];

    assert.equal(u.delay, 240);
    assert.equal(u.nextStopSeq, 12);
  });

  test('senza departure vale arrival', () => {
    const bytes = feed({
      entities: [tripUpdateEntity({ tripId: 'c1', stops: [{ seq: 5, arrival: 300 }] })],
    });

    const u = parseFeed(bytes, 'updates').updates[0];

    assert.equal(u.delay, 300);
    assert.equal(u.nextStopSeq, 5);
  });

  test('departure batte arrival quando ci sono tutti e due', () => {
    const bytes = feed({
      entities: [tripUpdateEntity({
        tripId: 'c1',
        stops: [{ seq: 5, arrival: 300, departure: 120 }],
      })],
    });

    assert.equal(parseFeed(bytes, 'updates').updates[0].delay, 120);
  });

  test('una fermata senza ritardo non interrompe la ricerca', () => {
    const bytes = feed({
      entities: [tripUpdateEntity({
        tripId: 'c1',
        stops: [{ seq: 1 }, { seq: 2 }, { seq: 3, departure: 90 }],
      })],
    });

    const u = parseFeed(bytes, 'updates').updates[0];

    assert.equal(u.delay, 90);
    assert.equal(u.nextStopSeq, 3);
  });

  test('un anticipo resta negativo', () => {
    // protobuf estende il segno a 64 bit: un int32 negativo e' un varint da
    // dieci byte. Un decoder che legge i 32 bit bassi senza reinterpretarli
    // col segno trasformerebbe due minuti di anticipo in un'era geologica.
    const bytes = feed({
      entities: [tripUpdateEntity({ tripId: 'c1', stops: [{ seq: 2, departure: -120 }] })],
    });

    assert.equal(parseFeed(bytes, 'updates').updates[0].delay, -120);
  });

  test('senza StopTimeUpdate vale il ritardo complessivo', () => {
    const bytes = feed({
      entities: [tripUpdateEntity({ tripId: 'c1', overallDelay: 45 })],
    });

    const u = parseFeed(bytes, 'updates').updates[0];

    assert.equal(u.delay, 45);
    assert.equal(u.nextStopSeq, null);
  });

  test('con nessun ritardo da nessuna parte il ritardo e null, non zero', () => {
    // Null vuol dire "il feed non lo sa" e a valle diventa lo stato 3,
    // "senza dati". Zero vuol dire "in orario", che e' un'altra cosa.
    const bytes = feed({
      entities: [tripUpdateEntity({ tripId: 'c1', stops: [{ seq: 1 }] })],
    });

    assert.equal(parseFeed(bytes, 'updates').updates[0].delay, null);
  });

  test('una corsa cancellata si riconosce', () => {
    const bytes = feed({
      entities: [tripUpdateEntity({ tripId: 'c1', schedRel: 3 })],
    });

    const u = parseFeed(bytes, 'updates').updates[0];

    assert.equal(u.canceled, true);
    assert.equal(u.trip.schedRel, 3);
  });

  test('un TripUpdate senza corsa non entra nell elenco', () => {
    const bytes = feed({
      entities: [(entity) => entity.message(3, (u) => u.int32(5, 30))],
    });

    assert.equal(parseFeed(bytes, 'updates').updates.length, 0);
  });

  test('start_time oltre le 24 arriva cosi com e', () => {
    // 2.709 corse del feed finiscono dopo mezzanotte, e l'ultima alle 30:10.
    // Il troncamento a 24 e' il modo classico di perdere le notturne.
    const bytes = feed({
      entities: [tripUpdateEntity({ tripId: 'notturna', startTime: '25:30:00' })],
    });

    assert.equal(parseFeed(bytes, 'updates').updates[0].trip.startTime, '25:30:00');
  });

  test('con want updates i veicoli non si decodificano', () => {
    const bytes = feed({
      entities: [vehicleEntity({ tripId: 'v1' }), tripUpdateEntity({ tripId: 'u1' })],
    });

    const out = parseFeed(bytes, 'updates');

    assert.equal(out.updates.length, 1);
    assert.equal(out.vehicles.length, 0);
  });
});

describe('robustezza del wire format', () => {
  test('un varint lungo si legge senza perdere bit', () => {
    const bytes = feed({ timestamp: 2 ** 40 + 12345, entities: [] });
    assert.equal(parseFeed(bytes, 'header').timestamp, 2 ** 40 + 12345);
  });

  test('un wire type che non esiste fa fallire il parse, non impazzire', () => {
    // Meglio nessun dato che dati letti col righello sbagliato: a valle il
    // chiamante tiene lo snapshot precedente.
    const bytes = proto((f) => {
      f.parts.push(Uint8Array.from([(1 << 3) | 6]));
    });

    assert.throws(() => parseFeed(bytes, 'header'), /wire type/);
  });

  test('un feed vuoto non e un errore', () => {
    const out = parseFeed(new Uint8Array(0), 'vehicles');
    assert.equal(out.vehicles.length, 0);
    assert.equal(out.timestamp, null);
  });
});
