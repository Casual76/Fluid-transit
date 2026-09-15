/**
 * Il fissaggio incrociato del formato CONGELATO: veicoli e ritardi.
 *
 * Questo e' il formato che leggono le versioni gia' installate. La 1.1.0 che
 * qualcuno ha sul telefono verifica `recordSize == 40` e `== 32` e legge i
 * campi a offset fissi: se un giorno il Worker scrivesse un byte diverso,
 * quelle copie si romperebbero e non ci sarebbe modo di aggiustarle da
 * remoto — il codice non si aggiorna dal manifest, solo il comportamento.
 *
 * Per le previsioni (kind 4) lo stesso lavoro lo fa `golden-predictions.mjs`;
 * li' il rischio e' "minuti sbagliati", qui e' "app installate che smettono
 * di funzionare", che e' peggio.
 *
 * Il feed finto sotto non e' una fermata sola: ci sono dentro i casi limite
 * che il formato deve reggere, quelli che si sbagliano davvero — un veicolo
 * senza corsa, una direzione ignota, un orario oltre le 24, un ritardo che
 * sfonda il campo, una corsa cancellata.
 *
 *   node tools/golden-snapshot.mjs
 */

import { buildSnapshot, readHeader, sliceSection, fnv64 } from '../src/snapshot.js';

const vp = {
  timestamp: 1700000000,
  vehicles: [
    // Tutto dichiarato: il caso normale.
    {
      trip: { tripId: 'corsa-normale', routeId: 'linea-6', startTime: '08:15:00', direction: 0, schedRel: 0 },
      lat: 43.771389,
      lon: 11.254167,
      bearing: 137,
      timestamp: 1699999970, // 30 s prima della generazione
      speed: 8.3,
      vehicleId: 'bus-4821',
    },
    // Niente corsa, niente rotta, niente id: il feed lo fa davvero, e il
    // mezzo va disegnato lo stesso, solo senza linea.
    {
      trip: {},
      lat: -43.5,
      lon: -11.25,
      bearing: null,
      timestamp: null,
      speed: null,
      vehicleId: null,
    },
    // Rilevamento oltre i 360 gradi e velocita' altissima: i due campi vanno
    // ridotti, non scritti come capita.
    {
      trip: { tripId: 'corsa-notturna', routeId: 'linea-37', startTime: '25:10:00', direction: 1, schedRel: 1 },
      lat: 43.8,
      lon: 11.0,
      bearing: 725,
      timestamp: 1699930000, // di quasi venti ore fa: l'eta' sfonda il campo
      speed: 9000,
      vehicleId: 'bus-7',
    },
  ],
};

const tu = {
  timestamp: 1699999990,
  updates: [
    { trip: { tripId: 'corsa-normale', routeId: 'linea-6', startTime: '08:15:00', direction: 0 }, delay: 120, canceled: false, nextStopSeq: 3 },
    { trip: { tripId: 'corsa-cancellata', routeId: 'linea-12', startTime: '09:00:00', direction: 1 }, delay: null, canceled: true, nextStopSeq: null },
    { trip: { tripId: 'corsa-senza-dati', routeId: 'linea-37', startTime: '25:10:00', direction: null }, delay: null, canceled: false, nextStopSeq: 4 },
    // Un ritardo assurdo: il campo e' a 16 bit e va saturato, non troncato.
    { trip: { tripId: 'corsa-impossibile', routeId: 'linea-6', startTime: '23:59:59', direction: 0 }, delay: 99999, canceled: false, nextStopSeq: 65535 },
    // E uno assurdo dall'altra parte.
    { trip: { tripId: 'corsa-anticipata', routeId: 'linea-6', startTime: '00:00:00', direction: 0 }, delay: -99999, canceled: false, nextStopSeq: 0 },
  ],
};

const snapshot = buildSnapshot({ generatedAt: 1700000042, vp, tu, alertsBytes: new Uint8Array(0), alTimestamp: 0, flags: 0 });
const header = readHeader(snapshot);

for (const [nome, kind] of [['veicoli', 1], ['ritardi', 2]]) {
  const sec = sliceSection(snapshot, header, kind);
  const hex = Buffer.from(sec).toString('hex');
  console.log(`// ${nome}: ${sec.length} B, ${kind === 1 ? header.vehicleCount : header.delayCount} record`);
  for (let i = 0; i < hex.length; i += 96) {
    console.log(`            "${hex.slice(i, i + 96)}" +`);
  }
  console.log('');
}

// Le chiavi dei mezzi si rileggono dai byte appena scritti invece di
// ricalcolarle: una seconda copia di fnv32 qui potrebbe sbagliare insieme
// alla prima, e il confronto non direbbe niente.
const vView = new DataView(snapshot.buffer, snapshot.byteOffset, snapshot.byteLength);
console.log('// chiavi dei mezzi:');
for (let i = 0; i < header.vehicleCount; i++) {
  const o = header.vehiclesOff + i * 40;
  console.log(`//   ${vp.vehicles[i].vehicleId}: ${vView.getInt32(o + 36, true)}`);
}

console.log('// quello che il lettore deve trovare:');
for (const id of ['corsa-normale', 'corsa-notturna', 'corsa-cancellata', 'corsa-senza-dati', 'corsa-impossibile', 'corsa-anticipata', 'linea-6', 'linea-12', 'linea-37']) {
  console.log(`//   ${id}: ${fnv64(id)}L`);
}
