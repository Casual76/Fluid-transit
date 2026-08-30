/**
 * Scanner GTFS-Realtime minimale: legge solo il livello superiore del
 * FeedMessage senza costruire il grafo di oggetti.
 *
 * Non e' una libreria protobuf. E' deliberatamente parziale: salta ogni
 * campo che non serve. La stessa tecnica (CodedInputStream in streaming,
 * campi non usati saltati) e' quella prevista dal piano per il parsing
 * lato app in stato DIRECT e per il cron della Worker in Fase 4 — qui
 * serve a rispondere a due domande dello spike: i byte che arrivano sono
 * davvero un feed valido, e quanto e' vecchio il timestamp dell'origine.
 *
 * FeedMessage { header = 1 (FeedHeader), entity = 2 (repeated FeedEntity) }
 * FeedHeader  { gtfs_realtime_version = 1 (string), incrementality = 2,
 *               timestamp = 3 (uint64) }
 */

const WIRE_VARINT = 0;
const WIRE_I64 = 1;
const WIRE_LEN = 2;
const WIRE_I32 = 5;

class Reader {
  constructor(bytes, start = 0, end = bytes.length) {
    this.b = bytes;
    this.p = start;
    this.end = end;
  }
  get eof() {
    return this.p >= this.end;
  }
  varint() {
    let result = 0;
    let shift = 0;
    while (this.p < this.end) {
      const byte = this.b[this.p++];
      // Number resta esatto fino a 2^53: i timestamp Unix in secondi ci
      // stanno comodamente, e nessun altro campo letto qui e' grande.
      result += (byte & 0x7f) * Math.pow(2, shift);
      if ((byte & 0x80) === 0) return result;
      shift += 7;
      if (shift > 63) throw new Error('varint troppo lungo');
    }
    throw new Error('varint troncato');
  }
  /**
   * Consuma un campo lunghezza-delimitato e restituisce l'inizio del suo
   * contenuto. La lunghezza va letta in una variabile prima di spostare il
   * cursore: `this.p += this.varint()` in JavaScript valuta `this.p` PRIMA
   * di chiamare `varint()`, che nel frattempo lo ha gia' avanzato, e somma
   * quindi la lunghezza all'offset vecchio. Il disallineamento e' di pochi
   * byte e si manifesta lontano dalla causa.
   */
  enterLengthDelimited() {
    const len = this.varint();
    const start = this.p;
    this.p = start + len;
    return { start, len };
  }
  /** Salta il valore del campo appena letto, qualunque sia il wire type. */
  skip(wireType) {
    switch (wireType) {
      case WIRE_VARINT:
        this.varint();
        return;
      case WIRE_I64:
        this.p += 8;
        return;
      case WIRE_LEN:
        this.enterLengthDelimited();
        return;
      case WIRE_I32:
        this.p += 4;
        return;
      default:
        throw new Error('wire type sconosciuto: ' + wireType);
    }
  }
}

function readHeader(bytes, start, end) {
  const r = new Reader(bytes, start, end);
  const header = { version: null, incrementality: 0, timestamp: null };
  while (!r.eof) {
    const key = r.varint();
    const field = key >>> 3;
    const wire = key & 7;
    if (field === 1 && wire === WIRE_LEN) {
      const { start, len } = r.enterLengthDelimited();
      header.version = new TextDecoder().decode(bytes.subarray(start, start + len));
    } else if (field === 2 && wire === WIRE_VARINT) {
      header.incrementality = r.varint();
    } else if (field === 3 && wire === WIRE_VARINT) {
      header.timestamp = r.varint();
    } else {
      r.skip(wire);
    }
  }
  return header;
}

/**
 * @param {Uint8Array} bytes corpo grezzo della risposta
 * @returns {{ok: boolean, error?: string, header?: object, entityCount?: number}}
 */
export function peekFeed(bytes) {
  try {
    const r = new Reader(bytes);
    let header = null;
    let entityCount = 0;
    while (!r.eof) {
      const at = r.p;
      const key = r.varint();
      const field = key >>> 3;
      const wire = key & 7;
      if (wire !== WIRE_LEN && field <= 2) {
        // L'offset serve: un disallineamento si manifesta sempre lontano
        // dalla causa, e senza la posizione non si sa nemmeno dove guardare.
        return { ok: false, error: `campo ${field} con wire type ${wire} inatteso a byte ${at}` };
      }
      if (field === 1) {
        const { start, len } = r.enterLengthDelimited();
        header = readHeader(bytes, start, start + len);
      } else if (field === 2) {
        // Le entity non vengono aperte: se ne salta il contenuto. E' la
        // verifica piu' economica che il corpo sia strutturalmente coerente
        // fino all'ultimo byte.
        entityCount++;
        r.enterLengthDelimited();
      } else {
        r.skip(wire);
      }
    }
    if (r.p !== bytes.length) {
      return { ok: false, error: 'lunghezza incoerente: scansione finita fuori dal buffer' };
    }
    return { ok: true, header, entityCount };
  } catch (e) {
    return { ok: false, error: String(e && e.message ? e.message : e) };
  }
}
