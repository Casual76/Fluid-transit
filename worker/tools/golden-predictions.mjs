/**
 * Il fissaggio incrociato del formato delle previsioni.
 *
 * `predictions.js` scrive la sezione e `RtPredictionCodec.kt` la legge. Le due
 * implementazioni non condividono una riga: se divergono, il sintomo non e'
 * un errore ma minuti sbagliati, e si cercherebbe la causa dappertutto tranne
 * che nel formato — e' la stessa ragione per cui gli hash sono inchiodati in
 * `HashCompatTest.kt` e in `worker/test/snapshot.test.js`.
 *
 * Questo script costruisce una sezione da un feed finto ma completo (una corsa
 * normale, una cancellata, una con una fermata saltata, una senza dati) e la
 * stampa in esadecimale insieme ai valori che ci si aspetta di rileggere. Il
 * risultato si incolla nel test Kotlin.
 *
 *   node tools/golden-predictions.mjs
 */

import { buildPredictions } from '../src/predictions.js';
import { fnv64 } from '../src/snapshot.js';

/** Un feed finto, ma con dentro tutti i casi che il formato deve reggere. */
const tu = {
  timestamp: 1700000000,
  updates: [
    {
      trip: { tripId: 'corsa-normale', routeId: 'linea-6', startTime: '08:15:00', direction: 0 },
      overallDelay: 120,
      stops: [
        { seq: 1, delay: 120, time: null, stopId: 'fermata-a', rel: 0, from: 1 },
        { seq: 2, delay: 120, time: null, stopId: 'fermata-b', rel: 0, from: 1 },
        { seq: 3, delay: 90, time: null, stopId: 'fermata-c', rel: 0, from: 1 },
        { seq: 7, delay: -30, time: null, stopId: 'fermata-g', rel: 0, from: 0 },
      ],
    },
    {
      trip: { tripId: 'corsa-cancellata', routeId: 'linea-12', startTime: '09:00:00', direction: 1 },
      canceled: true,
      overallDelay: null,
      stops: [],
    },
    {
      trip: { tripId: 'corsa-con-salto', routeId: 'linea-6', startTime: '10:30:00', direction: 0 },
      overallDelay: 60,
      stops: [
        { seq: 1, delay: 60, time: null, stopId: 'fermata-a', rel: 0, from: 1 },
        { seq: 2, delay: null, time: null, stopId: 'fermata-b', rel: 1, from: -1 },
        { seq: 3, delay: 45, time: null, stopId: 'fermata-c', rel: 0, from: 1 },
      ],
    },
    {
      trip: { tripId: 'corsa-senza-dati', routeId: 'linea-37', startTime: '25:10:00', direction: 1 },
      overallDelay: null,
      stops: [{ seq: 4, delay: null, time: null, stopId: 'fermata-d', rel: 2, from: -1 }],
    },
  ],
};

const { bytes, stats } = buildPredictions({ generatedAt: 1700000042, tu });

const hex = Buffer.from(bytes).toString('hex');
console.log('// byte:', bytes.length, ' stats:', JSON.stringify(stats));
console.log('');
for (let i = 0; i < hex.length; i += 96) {
  console.log(`            "${hex.slice(i, i + 96)}" +`);
}
console.log('');
console.log('// quello che il lettore deve trovare:');
for (const u of tu.updates) {
  console.log(`//   ${u.trip.tripId}: hash ${fnv64(u.trip.tripId)}  route ${fnv64(u.trip.routeId)}`);
}
