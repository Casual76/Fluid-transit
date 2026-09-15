/**
 * Uno scrittore di protobuf minimo, per i test.
 *
 * Il Worker legge GTFS-RT a mano sul wire format, senza le classi generate:
 * per provarlo serviva un modo di FABBRICARE quei byte. Tirare dentro
 * protobufjs solo per i test avrebbe messo in gioco un secondo lettore, cioe'
 * avrebbe provato la nostra lettura contro la scrittura di qualcun altro
 * invece che contro il formato.
 *
 * E' il gemello di `app/src/test/.../Proto.kt`: quando uno dei due cresce,
 * cresce anche l'altro.
 */

class Writer {
  constructor() {
    this.parts = [];
  }

  varint(field, value) {
    this.tag(field, 0);
    this.writeVarint(BigInt(value));
    return this;
  }

  /**
   * Un int32 negativo. Protobuf lo codifica con l'estensione di segno a 64
   * bit, cioe' dieci byte: e' esattamente il caso che rompe i decoder
   * scritti in fretta, e i ritardi negativi (i bus in anticipo) esistono.
   */
  int32(field, value) {
    this.tag(field, 0);
    this.writeVarint(BigInt.asUintN(64, BigInt(value)));
    return this;
  }

  string(field, value) {
    return this.bytes(field, new TextEncoder().encode(value));
  }

  bytes(field, value) {
    this.tag(field, 2);
    this.writeVarint(BigInt(value.length));
    this.parts.push(value);
    return this;
  }

  float(field, value) {
    this.tag(field, 5);
    const b = new Uint8Array(4);
    new DataView(b.buffer).setFloat32(0, value, true);
    this.parts.push(b);
    return this;
  }

  fixed64(field, value) {
    this.tag(field, 1);
    const b = new Uint8Array(8);
    new DataView(b.buffer).setBigUint64(0, BigInt(value), true);
    this.parts.push(b);
    return this;
  }

  message(field, build) {
    const inner = new Writer();
    build(inner);
    return this.bytes(field, inner.finish());
  }

  finish() {
    let len = 0;
    for (const p of this.parts) len += p.length;
    const out = new Uint8Array(len);
    let o = 0;
    for (const p of this.parts) {
      out.set(p, o);
      o += p.length;
    }
    return out;
  }

  tag(field, wire) {
    this.writeVarint(BigInt((field << 3) | wire));
  }

  writeVarint(v) {
    const b = [];
    let x = v;
    for (;;) {
      const byte = Number(x & 0x7fn);
      x >>= 7n;
      if (x === 0n) {
        b.push(byte);
        break;
      }
      b.push(byte | 0x80);
    }
    this.parts.push(Uint8Array.from(b));
  }
}

export function proto(build) {
  const w = new Writer();
  build(w);
  return w.finish();
}

/** Un FeedMessage con l'intestazione e le entita' che gli passi. */
export function feed({ version = '2.0', timestamp = 1_700_000_000, entities = [] }) {
  return proto((f) => {
    f.message(1, (h) => {
      h.string(1, version);
      h.varint(3, timestamp);
    });
    entities.forEach((e, i) => f.message(2, (entity) => {
      entity.string(1, `e${i}`);
      e(entity);
    }));
  });
}

/** Una VehiclePosition dentro una FeedEntity (campo 4). */
export function vehicleEntity({
  tripId = null, routeId = null, startTime = null, direction = null, schedRel = null,
  lat = 43.7696, lon = 11.2558, bearing = null, speed = null,
  timestamp = null, vehicleId = null,
}) {
  return (entity) => entity.message(4, (v) => {
    if (tripId !== null || routeId !== null || startTime !== null ||
        direction !== null || schedRel !== null) {
      v.message(1, (t) => {
        if (tripId !== null) t.string(1, tripId);
        if (startTime !== null) t.string(2, startTime);
        if (schedRel !== null) t.varint(4, schedRel);
        if (routeId !== null) t.string(5, routeId);
        if (direction !== null) t.varint(6, direction);
      });
    }
    if (lat !== null || lon !== null) {
      v.message(2, (p) => {
        if (lat !== null) p.float(1, lat);
        if (lon !== null) p.float(2, lon);
        if (bearing !== null) p.float(3, bearing);
        if (speed !== null) p.float(5, speed);
      });
    }
    if (timestamp !== null) v.varint(5, timestamp);
    if (vehicleId !== null) v.message(8, (d) => d.string(1, vehicleId));
  });
}

/**
 * Un TripUpdate dentro una FeedEntity (campo 3).
 *
 * `stops` e' un elenco di { seq, stopId, arrival, departure, schedRel }: i
 * numeri sono ritardi in secondi, `null` vuol dire "il campo non c'e'".
 */
export function tripUpdateEntity({
  tripId = null, routeId = null, startTime = null, direction = null, schedRel = null,
  stops = [], overallDelay = null, timestamp = null,
}) {
  return (entity) => entity.message(3, (u) => {
    if (tripId !== null || routeId !== null || startTime !== null ||
        direction !== null || schedRel !== null) {
      u.message(1, (t) => {
        if (tripId !== null) t.string(1, tripId);
        if (startTime !== null) t.string(2, startTime);
        if (schedRel !== null) t.varint(4, schedRel);
        if (routeId !== null) t.string(5, routeId);
        if (direction !== null) t.varint(6, direction);
      });
    }
    for (const s of stops) {
      u.message(2, (stu) => {
        if (s.seq !== undefined && s.seq !== null) stu.varint(1, s.seq);
        if (s.arrival !== undefined && s.arrival !== null) {
          stu.message(2, (e) => e.int32(1, s.arrival));
        }
        if (s.departure !== undefined && s.departure !== null) {
          stu.message(3, (e) => e.int32(1, s.departure));
        }
        if (s.stopId !== undefined && s.stopId !== null) stu.string(4, s.stopId);
        if (s.schedRel !== undefined && s.schedRel !== null) stu.varint(5, s.schedRel);
      });
    }
    if (timestamp !== null) u.varint(4, timestamp);
    if (overallDelay !== null) u.int32(5, overallDelay);
  });
}
