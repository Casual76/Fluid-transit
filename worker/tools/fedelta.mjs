/**
 * Il banco di fedelta': i nostri minuti combaciano con quelli della fonte?
 *
 * E' la risposta alla domanda "non so nemmeno se i dati sono accurati", ed e'
 * l'unica che non si puo' dare guardando l'app: qualunque cosa mostri, la si
 * sta confrontando con se' stessa.
 *
 * Quindi si scaricano due cose e si confrontano:
 *
 *   1. il feed GTFS-RT **grezzo** della Regione, letto qui, adesso;
 *   2. la sezione `rt/v1/predictions` servita dal nostro proxy, che e' quello
 *      che l'app legge davvero.
 *
 * Per ogni corsa e per ogni fermata si confronta il ritardo. Zero differenze
 * vuol dire che fra l'origine e il telefono non si perde e non si inventa
 * niente. Le differenze che restano diventano un elenco con un nome, invece
 * che una sensazione.
 *
 * Uso:
 *   node tools/fedelta.mjs                    # contro il proxy in produzione
 *   node tools/fedelta.mjs --max-eta 300      # tollera 5 minuti di sfasamento
 *   node tools/fedelta.mjs --json fuori.json  # scrive anche il verdetto
 *
 * Esce con 1 se le differenze superano la soglia: cosi' puo' fare da cancello.
 *
 * Con `--json` scrive anche il verdetto in un file. Serve a farlo arrivare
 * all'utente: il confronto risponde alla domanda "non so nemmeno se i dati
 * sono accurati", e una risposta che vive solo nei log di un workflow non la
 * legge nessuno.
 */

import { gunzipSync } from 'node:zlib';
import { Buffer } from 'node:buffer';
import { writeFileSync } from 'node:fs';

import { parseFeed } from '../src/gtfsrt.js';
import { fnv64 } from '../src/snapshot.js';
import {
  PRED_HEADER_LEN,
  PRED_TRIP_RECORD,
  PRED_POINT_RECORD,
  NO_DELAY,
} from '../src/predictions.js';

const ORIGIN = 'https://regionetoscana.smartregion.toscana.it/mobility/artifacts/gtfs-rt';
const PROXY = 'https://fluid-transit-rt.fluid-transit.workers.dev/rt/v1';

/** Oltre questo scarto fra i due timestamp il confronto non ha senso. */
const DEFAULT_MAX_SKEW_SECONDS = 180;

/**
 * Sotto questi punti confrontati il banco non ha visto abbastanza.
 *
 * Alle due di notte la Regione pubblica ZERO corse, e il banco confrontava
 * zero punti, trovava zero differenze e dichiarava "i minuti che l'app legge
 * sono quelli che la Regione pubblica". E' vero e non vuol dire niente: con
 * la stessa logica, un proxy completamente rotto che serve una sezione vuota
 * passerebbe. Un cancello che non puo' fallire non e' un cancello.
 *
 * Cinquanta punti sono pochi perfino per la notte fonda e tantissimi perche'
 * un difetto sistematico si veda.
 */
const MIN_POINTS = 50;

/**
 * Quante differenze si tollerano, in parti per mille.
 *
 * Non zero, e non per pigrizia: i due lati si leggono in due istanti diversi,
 * e nel mezzo l'origine puo' essersi rigenerata. Una corsa che compare da una
 * parte e non dall'altra e' normale; un ritardo DIVERSO sulla stessa corsa e
 * sulla stessa fermata no, ed e' quello che si conta.
 */
const DEFAULT_MAX_DIFF_PER_MILLE = 5;

function arg(name, fallback) {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 && process.argv[i + 1] ? Number(process.argv[i + 1]) : fallback;
}

function argText(name) {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : null;
}

/**
 * Il verdetto su file, per chi non legge i log di un workflow.
 *
 * Le chiavi sono corte perche' questo file lo scarica un telefono, e sono
 * quelle che servono a una riga di schermata: quando, quanti punti, quante
 * differenze, e come e' andata.
 */
/**
 * Il codice di uscita si imposta, non si spara.
 *
 * `process.exit()` chiamato subito dopo una scrittura su stdout fa crollare
 * Node su Windows con un'asserzione di libuv, e il codice che esce e' 127
 * invece di quello scelto: cioe' il banco, lanciato a mano, mentiva sul
 * proprio verdetto. Impostare `exitCode` e tornare lascia che il processo
 * finisca di scrivere e chiuda con il numero giusto ovunque.
 */
function adesso() {
  return Math.floor(Date.now() / 1000);
}

function scriviVerdetto(percorso, dati) {
  if (!percorso) return;
  writeFileSync(percorso, `${JSON.stringify(dati, null, 2)}
`);
  console.log(`
verdetto scritto in ${percorso}`);
}

/**
 * I byte di una risposta, scompattati se serve.
 *
 * Il proxy serve le sezioni gia' compresse (`content-encoding: gzip`), e a
 * seconda di come la richiesta ha negoziato la codifica arrivano compresse
 * oppure no: misurato, due chiamate di fila allo stesso URL possono dare le
 * due cose. Il magic di gzip e' due byte: si guarda, invece di fidarsi
 * dell'intestazione.
 */
async function fetchBytes(url) {
  const res = await fetch(url, { headers: { 'User-Agent': 'FluidTransit-fedelta/1' } });
  if (!res.ok) throw new Error(`${url}: HTTP ${res.status}`);
  const raw = new Uint8Array(await res.arrayBuffer());
  if (raw.length > 2 && raw[0] === 0x1f && raw[1] === 0x8b) {
    return new Uint8Array(gunzipSync(Buffer.from(raw)));
  }
  return raw;
}

/** La sezione delle previsioni, riletta. Lo specchio di `buildPredictions`. */
function readPredictions(bytes) {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  if (bytes[0] !== 0x46 || bytes[1] !== 0x54 || bytes[2] !== 0x52 || bytes[3] !== 0x54) {
    throw new Error('magic sbagliato: non e' + "' una sezione FTRT");
  }
  const tripCount = view.getUint32(16, true);
  const pointCount = view.getUint32(24, true);
  const feedTimestamp = view.getUint32(12, true);
  const pointsOff = PRED_HEADER_LEN + tripCount * PRED_TRIP_RECORD;

  const byTrip = new Map();
  for (let i = 0; i < tripCount; i++) {
    const o = PRED_HEADER_LEN + i * PRED_TRIP_RECORD;
    const tripHash = view.getBigInt64(o, true);
    const firstPoint = view.getUint32(o + 20, true);
    const n = view.getUint16(o + 24, true);
    const overall = view.getInt16(o + 28, true);

    // I ritardi viaggiano come differenze: si risommano, come fa l'app.
    const points = [];
    let running = 0;
    for (let k = 0; k < n; k++) {
      const po = pointsOff + (firstPoint + k) * PRED_POINT_RECORD;
      running += view.getInt16(po + 2, true);
      points.push({
        seq: view.getUint16(po, true),
        delay: running,
        rel: view.getUint8(po + 4),
      });
    }
    byTrip.set(tripHash, {
      overall: overall === NO_DELAY ? null : overall,
      points,
      status: view.getUint8(o + 26),
    });
  }
  return { tripCount, pointCount, feedTimestamp, byTrip };
}

/**
 * Le previsioni del feed grezzo, ridotte come le riduce il proxy.
 *
 * NON si riusa `compressPoints`: confrontare il nostro output col nostro
 * output non prova niente. Qui si rifa' la stessa domanda in modo
 * indipendente — "per questa corsa e questa sequenza, che ritardo dice il
 * protobuf?" — e si confronta il risultato.
 */
function rawDelays(tu) {
  const byTrip = new Map();
  for (const u of tu.updates) {
    if (!u.trip || !u.trip.tripId) continue;
    const perSeq = new Map();
    for (const s of u.stops || []) {
      if (s.seq === null || s.seq === undefined) continue;
      if (s.delay === null || s.delay === undefined) continue;
      perSeq.set(s.seq, s.delay);
    }
    byTrip.set(fnv64(u.trip.tripId), { perSeq, overall: u.overallDelay ?? null });
  }
  return byTrip;
}

async function main() {
  const maxSkew = arg('max-eta', DEFAULT_MAX_SKEW_SECONDS);
  const maxPerMille = arg('max-diff', DEFAULT_MAX_DIFF_PER_MILLE);
  const jsonOut = argText('json');

  const [rawBytes, predBytes] = await Promise.all([
    fetchBytes(`${ORIGIN}/trip-updates`),
    fetchBytes(`${PROXY}/predictions`),
  ]);

  const tu = parseFeed(rawBytes, 'updates');
  const pred = readPredictions(predBytes);
  const raw = rawDelays(tu);

  const skew = Math.abs((tu.timestamp || 0) - (pred.feedTimestamp || 0));
  console.log(`origine:  ${tu.updates.length} corse, timestamp ${tu.timestamp}`);
  console.log(`proxy:    ${pred.tripCount} corse, ${pred.pointCount} punti, timestamp ${pred.feedTimestamp}`);
  console.log(`sfasamento fra i due: ${skew}s`);
  if (skew > maxSkew) {
    console.log(`\nI due lati vengono da due generazioni diverse (oltre ${maxSkew}s):`);
    console.log('il confronto non direbbe niente. Riprova fra un minuto.');
    scriviVerdetto(jsonOut, { at: adesso(), esito: 'sfasato', punti: 0, diversi: 0 });
    process.exitCode = 2;
    return;
  }

  let confrontati = 0;
  let diversi = 0;
  let soloOrigine = 0;
  let soloProxy = 0;
  const esempi = [];

  for (const [hash, r] of raw) {
    const p = pred.byTrip.get(hash);
    if (!p) {
      soloOrigine++;
      continue;
    }
    // Il proxy comprime: un punto vale finche' non ce n'e' un altro. Si
    // ricostruisce la propagazione, che e' esattamente la regola GTFS-RT che
    // applica anche l'app.
    const propagato = new Map();
    let corrente = null;
    const ordinati = [...p.points].sort((a, b) => a.seq - b.seq);
    let indice = 0;
    const sequenze = [...r.perSeq.keys()].sort((a, b) => a - b);
    for (const seq of sequenze) {
      while (indice < ordinati.length && ordinati[indice].seq <= seq) {
        corrente = ordinati[indice];
        indice++;
      }
      if (corrente) propagato.set(seq, corrente);
    }

    for (const [seq, atteso] of r.perSeq) {
      const trovato = propagato.get(seq);
      if (!trovato) continue; // fuori dalla finestra compressa: non e' un errore
      confrontati++;
      // Il taglio a +-16000 e' dichiarato: oltre, la differenza e' voluta.
      const clamped = Math.max(-16000, Math.min(16000, Math.round(atteso)));
      if (trovato.delay !== clamped) {
        diversi++;
        if (esempi.length < 10) {
          esempi.push(`corsa ${hash} seq ${seq}: origine ${atteso}s, proxy ${trovato.delay}s`);
        }
      }
    }
  }
  for (const hash of pred.byTrip.keys()) if (!raw.has(hash)) soloProxy++;

  const perMille = confrontati === 0 ? 0 : Math.round((diversi * 1000) / confrontati);
  console.log('');
  console.log(`punti confrontati:      ${confrontati}`);
  console.log(`ritardi diversi:        ${diversi} (${perMille}‰)`);
  console.log(`corse solo nell'origine: ${soloOrigine}`);
  console.log(`corse solo nel proxy:    ${soloProxy}`);
  for (const e of esempi) console.log(`  ${e}`);

  const minPoints = arg('min-punti', MIN_POINTS);
  if (confrontati < minPoints) {
    console.log(`
Solo ${confrontati} punti da confrontare, meno di ${minPoints}.`);
    console.log("Non e' un via libera: e' che non c'era niente da guardare.");
    console.log('Di notte la Regione pubblica zero corse. Riprova nelle ore di servizio.');
    scriviVerdetto(jsonOut, {
      at: adesso(),
      esito: 'poco',
      punti: confrontati,
      diversi,
    });
    process.exitCode = 2;
    return;
  }

  scriviVerdetto(jsonOut, {
    at: adesso(),
    esito: perMille > maxPerMille ? 'diverso' : 'uguale',
    punti: confrontati,
    diversi,
    perMille,
    soloOrigine,
    soloProxy,
  });

  if (perMille > maxPerMille) {
    console.log(`\nOltre la soglia di ${maxPerMille}‰: i nostri minuti non sono quelli della fonte.`);
    process.exitCode = 1;
    return;
  }
  console.log('\nI minuti che l\'app legge sono quelli che la Regione pubblica.');
}

main().catch((e) => {
  console.error('banco di fedelta\' fallito:', e.message);
  process.exitCode = 3;
});
