import assert from 'node:assert/strict';
import { test, describe } from 'node:test';
import { parseFeed } from '../src/gtfsrt.js';
import { fnv64 } from '../src/snapshot.js';
import {
  buildPredictions, compressPoints, readPredictionsHeader,
  PRED_HEADER_LEN, PRED_TRIP_RECORD, PRED_POINT_RECORD, NO_DELAY, MAX_POINTS,
} from '../src/predictions.js';
import { feed, tripUpdateEntity } from './proto.js';

/**
 * Le previsioni per fermata.
 *
 * E' il pezzo che decide se i minuti dell'app sono quelli del feed o una
 * nostra ricostruzione. Sotto ci sono due contratti: quello col formato
 * (offset, sentinella, ordinamento) e quello con la regola di propagazione
 * di GTFS-RT, che e' la ragione per cui la compattazione e' lecita.
 */

const NOW = 1_700_000_000;

/** I 32 bit bassi dell'hash: e' cosi' che viaggiano le ancore. */
const id32 = (id) => Number(BigInt.asUintN(32, fnv64(id)));

function build(entities) {
  const tu = parseFeed(feed({ timestamp: NOW - 25, entities }), 'updates');
  return buildPredictions({ generatedAt: NOW, tu });
}

/** Rilegge la sezione come la leggera' l'app. */
function read(bytes) {
  const h = readPredictionsHeader(bytes);
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const pointsOff = PRED_HEADER_LEN + h.tripCount * PRED_TRIP_RECORD;
  const trips = [];
  for (let i = 0; i < h.tripCount; i++) {
    const o = PRED_HEADER_LEN + i * PRED_TRIP_RECORD;
    const first = view.getUint32(o + 20, true);
    const count = view.getUint16(o + 24, true);
    const points = [];
    // I ritardi viaggiano come differenze: si risommano scorrendo, che e'
    // esattamente quello che fara' l'app.
    let running = 0;
    for (let k = 0; k < count; k++) {
      const po = pointsOff + (first + k) * PRED_POINT_RECORD;
      running += view.getInt16(po + 2, true);
      points.push({
        seq: view.getUint16(po, true),
        delay: running,
        rel: view.getUint8(po + 4),
        from: view.getUint8(po + 5),
      });
    }
    trips.push({
      tripHash: view.getBigInt64(o, true),
      routeHash: view.getBigInt64(o + 8, true),
      startTimeSec: view.getUint32(o + 16, true),
      status: view.getUint8(o + 26),
      direction: view.getUint8(o + 27),
      tripDelay: view.getInt16(o + 28, true),
      firstStopId32: view.getUint32(o + 32, true),
      lastStopId32: view.getUint32(o + 36, true),
      points,
    });
  }
  return { header: h, trips };
}

describe('intestazione', () => {
  test('dichiara versione, tipo e misura dei record', () => {
    const { bytes } = build([]);
    const h = readPredictionsHeader(bytes);

    assert.equal(h.version, 2, 'versione 2: non e una sezione a record fissi');
    assert.equal(h.kind, 4);
    assert.equal(h.headerLen, PRED_HEADER_LEN);
    assert.equal(h.tripRecord, PRED_TRIP_RECORD);
    assert.equal(h.pointRecord, PRED_POINT_RECORD);
    assert.equal(h.generatedAt, NOW);
    assert.equal(h.feedTimestamp, NOW - 25);
  });

  test('una sezione vuota e solo l intestazione', () => {
    const { bytes } = build([]);
    assert.equal(bytes.length, PRED_HEADER_LEN);
    assert.equal(readPredictionsHeader(bytes).tripCount, 0);
  });

  test('la lunghezza torna con i conteggi dichiarati', () => {
    const { bytes } = build([
      tripUpdateEntity({ tripId: 'a', stops: [{ seq: 1, stopId: 's1', departure: 60 }] }),
      tripUpdateEntity({
        tripId: 'b',
        stops: [
          { seq: 1, stopId: 's1', departure: 60 },
          { seq: 2, stopId: 's2', departure: 120 },
        ],
      }),
    ]);
    const h = readPredictionsHeader(bytes);

    assert.equal(h.tripCount, 2);
    assert.equal(h.pointCount, 3);
    assert.equal(
      bytes.length,
      PRED_HEADER_LEN + 2 * PRED_TRIP_RECORD + 3 * PRED_POINT_RECORD,
    );
  });

  test('readPredictionsHeader rifiuta i byte che non sono una sezione', () => {
    assert.equal(readPredictionsHeader(null), null);
    assert.equal(readPredictionsHeader(new Uint8Array(4)), null);
    assert.equal(readPredictionsHeader(new Uint8Array(PRED_HEADER_LEN)), null);
  });
});

describe('indice delle corse', () => {
  test('le corse sono ordinate per hash, per la ricerca binaria', () => {
    const ids = ['zeta', 'alfa', 'mu', 'beta', 'omega'];
    const { bytes } = build(ids.map((id) => tripUpdateEntity({
      tripId: id, stops: [{ seq: 1, stopId: 's', departure: 30 }],
    })));

    const hashes = read(bytes).trips.map((t) => t.tripHash);
    const sorted = [...hashes].sort((a, b) => (a < b ? -1 : a > b ? 1 : 0));

    assert.deepEqual(hashes, sorted);
    assert.equal(new Set(hashes).size, ids.length);
  });

  test('ogni corsa punta ai suoi punti e non a quelli di un altra', () => {
    const { bytes } = build([
      tripUpdateEntity({
        tripId: 'uno',
        stops: [{ seq: 1, stopId: 'a', departure: 60 }, { seq: 2, stopId: 'b', departure: 90 }],
      }),
      tripUpdateEntity({ tripId: 'due', stops: [{ seq: 7, stopId: 'z', departure: -30 }] }),
    ]);

    const trips = read(bytes).trips;
    const uno = trips.find((t) => t.tripHash === fnv64('uno'));
    const due = trips.find((t) => t.tripHash === fnv64('due'));

    assert.deepEqual(uno.points.map((p) => p.delay), [60, 90]);
    assert.deepEqual(due.points.map((p) => p.delay), [-30]);
    assert.equal(due.points[0].seq, 7);
  });

  test('la corsa porta linea, partenza e direzione', () => {
    const { bytes } = build([tripUpdateEntity({
      tripId: 'c1', routeId: 'r1', startTime: '08:30:00', direction: 1,
      stops: [{ seq: 1, stopId: 's', departure: 60 }],
    })]);

    const t = read(bytes).trips[0];

    assert.equal(t.routeHash, fnv64('r1'));
    assert.equal(t.startTimeSec, 8 * 3600 + 30 * 60);
    assert.equal(t.direction, 1);
  });

  test('start_time oltre le 24 non si tronca', () => {
    const { bytes } = build([tripUpdateEntity({
      tripId: 'notte', startTime: '25:30:00',
      stops: [{ seq: 1, stopId: 's', departure: 0 }],
    })]);

    assert.equal(read(bytes).trips[0].startTimeSec, 25 * 3600 + 30 * 60);
  });

  test('i campi ignoti sono i sentinella, non zero', () => {
    const { bytes } = build([tripUpdateEntity({
      tripId: 'c1', stops: [{ seq: 1, stopId: 's', departure: 60 }],
    })]);

    const t = read(bytes).trips[0];

    assert.equal(t.routeHash, 0n);
    assert.equal(t.startTimeSec, 0xffffffff);
    assert.equal(t.direction, 0xff);
    assert.equal(t.tripDelay, NO_DELAY, 'nessun delay complessivo dichiarato');
  });

  test('il ritardo complessivo viaggia a parte da quelli per fermata', () => {
    // Sono due cose diverse: uno e' "questa corsa, in generale", l'altro e'
    // "questa fermata". Fonderli vorrebbe dire non poter piu' dire all'utente
    // da dove viene il numero che sta guardando.
    const { bytes } = build([tripUpdateEntity({
      tripId: 'c1', overallDelay: 300,
      stops: [{ seq: 4, stopId: 's', departure: 120 }],
    })]);

    const t = read(bytes).trips[0];

    assert.equal(t.tripDelay, 300);
    assert.equal(t.points[0].delay, 120);
  });

  test('zero e assente non sono la stessa cosa', () => {
    const { bytes } = build([
      tripUpdateEntity({ tripId: 'puntuale', overallDelay: 0, stops: [] }),
      tripUpdateEntity({ tripId: 'muta', stops: [] }),
    ]);

    const trips = read(bytes).trips;
    const puntuale = trips.find((t) => t.tripHash === fnv64('puntuale'));
    const muta = trips.find((t) => t.tripHash === fnv64('muta'));

    assert.equal(puntuale.tripDelay, 0, 'in orario');
    assert.equal(muta.tripDelay, NO_DELAY, 'non lo sappiamo');
  });
});

describe('stato della corsa', () => {
  test('una corsa cancellata si dichiara tale', () => {
    const { bytes } = build([tripUpdateEntity({ tripId: 'c1', schedRel: 3 })]);
    assert.equal(read(bytes).trips[0].status, 1);
  });

  test('una corsa aggiunta si distingue da una normale', () => {
    const { bytes } = build([tripUpdateEntity({
      tripId: 'c1', schedRel: 1, stops: [{ seq: 1, stopId: 's', departure: 0 }],
    })]);
    assert.equal(read(bytes).trips[0].status, 2);
  });

  test('una corsa di cui non si sa niente ha il suo stato', () => {
    const { bytes } = build([tripUpdateEntity({ tripId: 'c1', stops: [{ seq: 1 }] })]);
    assert.equal(read(bytes).trips[0].status, 3);
  });

  test('una corsa con previsioni e in regola', () => {
    const { bytes } = build([tripUpdateEntity({
      tripId: 'c1', stops: [{ seq: 1, stopId: 's', departure: 60 }],
    })]);
    assert.equal(read(bytes).trips[0].status, 0);
  });
});

describe('punti di previsione', () => {
  test('fermata, sequenza e origine del numero arrivano tutte', () => {
    const { bytes } = build([tripUpdateEntity({
      tripId: 'c1', stops: [{ seq: 12, stopId: 'FERMATA-7', departure: 240 }],
    })]);

    const t = read(bytes).trips[0];
    const p = t.points[0];

    assert.equal(t.firstStopId32, id32('FERMATA-7'), 'ancora di testa');
    assert.equal(t.lastStopId32, id32('FERMATA-7'), 'unico punto: e anche la coda');
    assert.equal(p.seq, 12);
    assert.equal(p.delay, 240);
    assert.equal(p.rel, 0);
    assert.equal(p.from, 1, 'dalla partenza');
  });

  test('senza departure vale arrival, e si sa che e arrival', () => {
    const { bytes } = build([tripUpdateEntity({
      tripId: 'c1', stops: [{ seq: 3, stopId: 's', arrival: 90 }],
    })]);

    const p = read(bytes).trips[0].points[0];

    assert.equal(p.delay, 90);
    assert.equal(p.from, 0, 'dall arrivo');
  });

  test('senza stop_id il punto resta, con l hash a zero', () => {
    // Si tiene lo stesso: lo stop_sequence e' un ripiego peggiore ma
    // esistente, e buttare il punto vorrebbe dire perdere il ritardo.
    const { bytes } = build([tripUpdateEntity({
      tripId: 'c1', stops: [{ seq: 5, departure: 60 }],
    })]);

    const t = read(bytes).trips[0];

    assert.equal(t.firstStopId32, 0);
    assert.equal(t.points[0].seq, 5);
  });

  test('senza stop_sequence il punto resta, con il sentinella', () => {
    const { bytes } = build([tripUpdateEntity({
      tripId: 'c1', stops: [{ stopId: 'solo-id', departure: 60 }],
    })]);

    const t = read(bytes).trips[0];

    assert.equal(t.points[0].seq, 0xffff);
    assert.equal(t.firstStopId32, id32('solo-id'));
  });

  test('un anticipo resta negativo fino ai byte', () => {
    const { bytes } = build([tripUpdateEntity({
      tripId: 'c1', stops: [{ seq: 1, stopId: 's', departure: -240 }],
    })]);

    assert.equal(read(bytes).trips[0].points[0].delay, -240);
  });

  test('un ritardo assurdo si satura invece di traboccare', () => {
    // Il tetto dei punti e' piu' basso di quello del ritardo complessivo
    // (16.000 contro 32.000) perche' i punti viaggiano come differenze: due
    // valori agli estremi opposti darebbero uno scarto che in un i16 non ci
    // sta, e li' un traboccamento non sposta un punto, sfasa tutta la corsa
    // da quel punto in poi.
    const { bytes } = build([tripUpdateEntity({
      tripId: 'c1', stops: [{ seq: 1, stopId: 's', departure: 99999 }],
    })]);

    assert.equal(read(bytes).trips[0].points[0].delay, 16000);
  });

  test('le differenze si risommano anche a cavallo degli estremi', () => {
    const { bytes } = build([tripUpdateEntity({
      tripId: 'c1',
      stops: [
        { seq: 1, stopId: 'a', departure: 15000 },
        { seq: 2, stopId: 'b', departure: -15000 },
        { seq: 3, stopId: 'c', departure: 600 },
      ],
    })]);

    assert.deepEqual(
      read(bytes).trips[0].points.map((p) => p.delay),
      [15000, -15000, 600],
    );
  });

  test('una fermata SALTATA si dichiara, e non e un ritardo', () => {
    // E' il fatto nuovo che oggi non abbiamo: quella partenza non ci sara'.
    // Mostrarla in ritardo sarebbe peggio che non mostrarla.
    const { bytes } = build([tripUpdateEntity({
      tripId: 'c1',
      stops: [
        { seq: 1, stopId: 'a', departure: 60 },
        { seq: 2, stopId: 'b', schedRel: 1 },
        { seq: 3, stopId: 'c', departure: 60 },
      ],
    })]);

    const points = read(bytes).trips[0].points;

    assert.equal(points.length, 3);
    assert.equal(points[1].rel, 1, 'saltata');
    assert.equal(points[1].seq, 2);
  });

  test('una fermata senza dati interrompe la propagazione', () => {
    const { bytes } = build([tripUpdateEntity({
      tripId: 'c1',
      stops: [
        { seq: 1, stopId: 'a', departure: 60 },
        { seq: 2, stopId: 'b', schedRel: 2 },
      ],
    })]);

    const points = read(bytes).trips[0].points;

    assert.equal(points.length, 2);
    assert.equal(points[1].rel, 2);
  });
});

describe('le ancore delle fermate', () => {
  test('sono la prima e l ultima fermata dell elenco', () => {
    // Sono gli unici stop_id che viaggiano. Tenere l'hash su ogni punto
    // costava il doppio della sezione (24,4 kB compressi contro 14,4,
    // misurati sul feed vero): otto byte casuali che gzip non comprime.
    const { bytes } = build([tripUpdateEntity({
      tripId: 'c1',
      stops: [
        { seq: 4, stopId: 'testa', departure: 60 },
        { seq: 5, stopId: 'mezzo', departure: 120 },
        { seq: 6, stopId: 'coda', departure: 180 },
      ],
    })]);

    const t = read(bytes).trips[0];

    assert.equal(t.firstStopId32, id32('testa'));
    assert.equal(t.lastStopId32, id32('coda'));
    assert.deepEqual(t.points.map((p) => p.seq), [4, 5, 6]);
  });

  test('le ancore seguono i punti TENUTI, non quelli letti', () => {
    // Se la compattazione toglie l'ultimo punto, l'ancora di coda deve
    // seguirlo: altrimenti l'app verificherebbe la mappatura contro una
    // fermata che nella sezione non c'e'.
    const { bytes } = build([tripUpdateEntity({
      tripId: 'c1',
      stops: [
        { seq: 1, stopId: 'a', departure: 60 },
        { seq: 2, stopId: 'b', departure: 60 },
        { seq: 3, stopId: 'c', departure: 60 },
      ],
    })]);

    const t = read(bytes).trips[0];

    assert.equal(t.points.length, 1, 'le tre copie diventano una');
    assert.equal(t.firstStopId32, id32('a'));
    assert.equal(t.lastStopId32, id32('a'));
  });

  test('una corsa senza punti non ha ancore', () => {
    const { bytes } = build([tripUpdateEntity({ tripId: 'c1', overallDelay: 60 })]);
    const t = read(bytes).trips[0];

    assert.equal(t.firstStopId32, 0);
    assert.equal(t.lastStopId32, 0);
  });
});

describe('compattazione', () => {
  test('le ripetizioni non si scrivono', () => {
    // La regola di GTFS-RT e' che una previsione vale per le fermate
    // successive finche' non ce n'e' un'altra: non scrivere le copie non e'
    // perdere informazione, e' scrivere la stessa cosa senza ripeterla.
    const stops = [];
    for (let i = 1; i <= 30; i++) stops.push({ seq: i, stopId: `s${i}`, departure: 180 });
    const { bytes, stats } = build([tripUpdateEntity({ tripId: 'c1', stops })]);

    assert.equal(stats.rawPoints, 30);
    assert.equal(stats.points, 1);
    assert.equal(read(bytes).trips[0].points[0].delay, 180);
  });

  test('ogni cambio di ritardo si scrive', () => {
    const { bytes } = build([tripUpdateEntity({
      tripId: 'c1',
      stops: [
        { seq: 1, stopId: 'a', departure: 300 },
        { seq: 2, stopId: 'b', departure: 300 },
        { seq: 3, stopId: 'c', departure: 120 },
        { seq: 4, stopId: 'd', departure: 120 },
        { seq: 5, stopId: 'e', departure: 0 },
      ],
    })]);

    const points = read(bytes).trips[0].points;

    assert.deepEqual(points.map((p) => p.delay), [300, 120, 0]);
    assert.deepEqual(points.map((p) => p.seq), [1, 3, 5]);
  });

  test('dopo una fermata saltata la previsione si riafferma', () => {
    // Senza questo, la fermata dopo una saltata erediterebbe lo stato
    // "saltata" e sparirebbe dal tabellone anche se il bus ci passa.
    // Campi come li produce il parser: `delay` e `rel`, non `departure` e
    // `schedRel`, che sono i nomi del wire format.
    const points = compressPoints([
      { seq: 1, stopId: 'a', delay: 60, rel: 0 },
      { seq: 2, stopId: 'b', delay: null, rel: 1 },
      { seq: 3, stopId: 'c', delay: 60, rel: 0 },
      { seq: 4, stopId: 'd', delay: 60, rel: 0 },
    ])

    assert.deepEqual(points.map((p) => p.seq), [1, 2, 3]);
    assert.equal(points[2].rel, 0);
  });

  test('un punto che non dice niente non entra', () => {
    const points = compressPoints([
      { seq: 1, stopId: 'a', delay: null, rel: 0 },
      { seq: 2, stopId: 'b', delay: 60, rel: 0 },
    ]);

    assert.equal(points.length, 1);
    assert.equal(points[0].seq, 2);
  });

  test('zero e una previsione come le altre', () => {
    // "In orario" e' un'informazione: e' il feed che dice che il bus e'
    // puntuale, ed e' diverso da non saperne niente.
    const points = compressPoints([
      { seq: 1, stopId: 'a', delay: 120, rel: 0 },
      { seq: 2, stopId: 'b', delay: 0, rel: 0 },
    ]);

    assert.deepEqual(points.map((p) => p.delay), [120, 0]);
  });
});

describe('conteggi per la diagnostica', () => {
  test('dicono quanto il feed e davvero granulare', () => {
    const { stats } = build([
      tripUpdateEntity({
        tripId: 'varia',
        stops: [
          { seq: 1, stopId: 'a', departure: 300 },
          { seq: 2, stopId: 'b', departure: 200 },
          { seq: 3, stopId: 'c', departure: 100 },
        ],
      }),
      tripUpdateEntity({
        tripId: 'piatta',
        stops: [
          { seq: 1, stopId: 'a', departure: 60 },
          { seq: 2, stopId: 'b', departure: 60 },
          { seq: 3, stopId: 'c', departure: 60 },
        ],
      }),
      tripUpdateEntity({ tripId: 'solo-generale', overallDelay: 45 }),
      tripUpdateEntity({ tripId: 'muta' }),
    ]);

    assert.equal(stats.trips, 4);
    assert.equal(stats.rawPoints, 6);
    assert.equal(stats.points, 4, 'tre dalla corsa che varia, uno da quella piatta');
    assert.equal(stats.tripsWithPoints, 2);
    assert.equal(stats.tripsOnlyOverall, 1);
    assert.equal(stats.tripsNoData, 1);
  });

  test('contano le fermate senza stop_id e quelle con solo l orario', () => {
    const { stats } = build([
      tripUpdateEntity({ tripId: 'a', stops: [{ seq: 1, departure: 60 }] }),
      tripUpdateEntity({
        tripId: 'b',
        stops: [(() => ({ seq: 1, stopId: 's' }))()],
      }),
    ]);

    assert.equal(stats.pointsWithoutStopId, 1);
    assert.equal(stats.pointsTimeOnly, 0);
  });

  test('contano le fermate saltate', () => {
    const { stats } = build([tripUpdateEntity({
      tripId: 'c1',
      stops: [{ seq: 1, stopId: 'a', departure: 0 }, { seq: 2, stopId: 'b', schedRel: 1 }],
    })]);

    assert.equal(stats.skipped, 1);
  });
});

describe('la valvola sul peso', () => {
  function bigFeed(stopsPerTrip, trips) {
    const ents = [];
    for (let t = 0; t < trips; t++) {
      const stops = [];
      for (let i = 1; i <= stopsPerTrip; i++) {
        // Un valore diverso a ogni fermata: e' il caso peggiore, quello in
        // cui la compattazione non puo' togliere niente.
        stops.push({ seq: i, stopId: `s${t}-${i}`, departure: 60 + t * 3 + i * 7 });
      }
      ents.push(tripUpdateEntity({ tripId: `corsa-${t}`, stops }));
    }
    return ents;
  }

  test('sotto il tetto non si tocca niente', () => {
    const { stats } = build(bigFeed(20, 200));

    assert.equal(stats.points, 4000);
    assert.equal(stats.truncated, false);
    assert.equal(stats.pointsDropped, 0);
  });

  test('sopra il tetto si taglia la coda e lo si dichiara', () => {
    const { bytes, stats } = build(bigFeed(60, 1000));

    assert.equal(stats.rawPoints, 60000);
    assert.ok(stats.points <= MAX_POINTS, `${stats.points} punti oltre il tetto`);
    assert.equal(stats.truncated, true);
    assert.ok(stats.pointsDropped > 0);
    // Il bit va in chiaro nell'intestazione: l'app deve poter dire che le
    // previsioni sono parziali, invece di farle passare per complete.
    assert.equal(readPredictionsHeader(bytes).flags & 1, 1);
  });

  test('il taglio toglie la coda, non le corse', () => {
    // Una corsa senza previsioni sparirebbe del tutto dal live invece di
    // averne meno: il taglio deve essere uguale per tutti.
    const { bytes } = build(bigFeed(60, 1000));
    const trips = read(bytes).trips;

    assert.equal(trips.length, 1000);
    assert.ok(trips.every((t) => t.points.length > 0));
    const sizes = new Set(trips.map((t) => t.points.length));
    assert.equal(sizes.size, 1, 'stesso numero di punti per tutte');
  });

  test('i punti tagliati sono quelli lontani, non quelli vicini', () => {
    const { bytes } = build(bigFeed(60, 1000));
    const t = read(bytes).trips[0];

    // Le sequenze tenute partono da 1 e sono consecutive: la coda va via.
    assert.equal(t.points[0].seq, 1);
    assert.deepEqual(
      t.points.map((p) => p.seq),
      t.points.map((_, i) => i + 1),
    );
  });
});

describe('quello che non deve cambiare', () => {
  test('la proiezione a un numero solo resta identica', () => {
    // E' quella che alimenta /rt/v1/updates, cioe' le versioni dell'app
    // gia' installate. Derivandola dallo stesso elenco invece che da un
    // parse parziale, le due non possono piu' divergere -- ma deve dare
    // esattamente lo stesso risultato di prima.
    const bytes = feed({
      entities: [tripUpdateEntity({
        tripId: 'c1',
        stops: [
          { seq: 10 },
          { seq: 11, departure: 240 },
          { seq: 12, departure: 60 },
        ],
      })],
    });

    const u = parseFeed(bytes, 'updates').updates[0];

    assert.equal(u.delay, 240, 'il primo StopTimeUpdate con un ritardo');
    assert.equal(u.nextStopSeq, 11);
    assert.equal(u.stops.length, 3, 'ma adesso ci sono tutti');
  });
});
