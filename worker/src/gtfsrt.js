/**
 * Decoder GTFS-Realtime minimale, scritto a mano sul wire format protobuf.
 *
 * Niente protobufjs riflessivo e niente grafo di oggetti per 200k update:
 * il cron ha 30 s di CPU ma il principio del proxy e' fare il lavoro UNA
 * volta al minuto, non ad ogni richiesta. Si leggono solo i campi che il
 * formato di risposta usa; tutto il resto si salta a costo zero.
 *
 * Attenzione alle versioni: alerts e' GTFS-RT 2.0, gli altri due 1.0 — il
 * wire format e' identico, ma il parser non deve assumere una versione.
 */

// --- lettura wire format ---------------------------------------------------

/**
 * Legge un varint da `buf` a partire da `pos`.
 *
 * Ritorna { num, lo, pos }: `num` e' il valore come Number (esatto fino a
 * 2^53, sufficiente per i timestamp), `lo` sono i 32 bit bassi calcolati
 * senza perdita — servono per gli int32 negativi (i ritardi!), che protobuf
 * codifica come varint a 10 byte in complemento a due.
 */
function varint(buf, pos) {
  let num = 0;
  let lo = 0;
  let shift = 0;
  for (;;) {
    const b = buf[pos++];
    if (shift < 32) lo = (lo + (b & 0x7f) * 2 ** shift) >>> 0;
    num += (b & 0x7f) * 2 ** shift;
    if ((b & 0x80) === 0) break;
    shift += 7;
    if (shift > 70) throw new Error('varint troppo lungo');
  }
  return { num, lo, pos };
}

function skipField(buf, pos, wire) {
  switch (wire) {
    case 0: return varint(buf, pos).pos;
    case 1: return pos + 8;
    case 2: { const v = varint(buf, pos); return v.pos + v.num; }
    case 5: return pos + 4;
    default: throw new Error(`wire type ${wire} inatteso`);
  }
}

/** Itera i campi di un messaggio [start, end); cb ritorna la nuova pos o undefined per saltare. */
function fields(buf, start, end, cb) {
  let pos = start;
  while (pos < end) {
    const tag = varint(buf, pos);
    const field = tag.num >>> 3;
    const wire = tag.num & 7;
    const next = cb(field, wire, tag.pos);
    pos = next !== undefined ? next : skipField(buf, tag.pos, wire);
  }
}

function subMessage(buf, pos) {
  const len = varint(buf, pos);
  return { start: len.pos, end: len.pos + len.num };
}

const utf8 = new TextDecoder();

function stringAt(buf, pos) {
  const m = subMessage(buf, pos);
  return { value: utf8.decode(buf.subarray(m.start, m.end)), pos: m.end };
}

// --- messaggi GTFS-RT ------------------------------------------------------

/** TripDescriptor: trip_id(1), start_time(2), start_date(3), sched_rel(4), route_id(5), direction_id(6). */
function parseTripDescriptor(buf, start, end) {
  const t = { tripId: null, routeId: null, direction: null, startTime: null, schedRel: 0 };
  fields(buf, start, end, (field, wire, pos) => {
    switch (field) {
      case 1: { const s = stringAt(buf, pos); t.tripId = s.value; return s.pos; }
      case 2: { const s = stringAt(buf, pos); t.startTime = s.value; return s.pos; }
      case 4: { const v = varint(buf, pos); t.schedRel = v.num; return v.pos; }
      case 5: { const s = stringAt(buf, pos); t.routeId = s.value; return s.pos; }
      case 6: { const v = varint(buf, pos); t.direction = v.num; return v.pos; }
      default: return undefined;
    }
  });
  return t;
}

/** Position: latitude(1 float), longitude(2 float), bearing(3 float), speed(5 float). */
function parsePosition(buf, view, base, start, end) {
  const p = { lat: null, lon: null, bearing: null, speed: null };
  fields(buf, start, end, (field, wire, pos) => {
    if (wire !== 5) return undefined;
    const v = view.getFloat32(base + pos, true);
    if (field === 1) p.lat = v;
    else if (field === 2) p.lon = v;
    else if (field === 3) p.bearing = v;
    else if (field === 5) p.speed = v;
    return pos + 4;
  });
  return p;
}

/** VehicleDescriptor: id(1), label(2). Serve solo una chiave stabile per l'interpolazione. */
function parseVehicleDescriptor(buf, start, end) {
  const d = { id: null, label: null };
  fields(buf, start, end, (field, wire, pos) => {
    if (field === 1) { const s = stringAt(buf, pos); d.id = s.value; return s.pos; }
    if (field === 2) { const s = stringAt(buf, pos); d.label = s.value; return s.pos; }
    return undefined;
  });
  return d.id !== null ? d.id : d.label;
}

/** StopTimeEvent: delay(1 int32), time(2 int64). */
function parseStopTimeEvent(buf, start, end) {
  const e = { delay: null };
  fields(buf, start, end, (field, wire, pos) => {
    if (field === 1 && wire === 0) {
      const v = varint(buf, pos);
      e.delay = v.lo | 0; // int32: i 32 bit bassi, reinterpretati con segno
      return v.pos;
    }
    return undefined;
  });
  return e;
}

/**
 * Il FeedMessage intero. `want` sceglie cosa estrarre: 'vehicles' legge le
 * VehiclePosition, 'updates' i TripUpdate, 'header' solo l'intestazione.
 */
export function parseFeed(bytes, want) {
  const buf = bytes;
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const out = { timestamp: null, version: null, vehicles: [], updates: [] };

  fields(buf, 0, buf.length, (field, wire, pos) => {
    if (field === 1 && wire === 2) {
      // FeedHeader: gtfs_realtime_version(1), incrementality(2), timestamp(3)
      const m = subMessage(buf, pos);
      fields(buf, m.start, m.end, (f2, w2, p2) => {
        if (f2 === 1 && w2 === 2) { const s = stringAt(buf, p2); out.version = s.value; return s.pos; }
        if (f2 === 3 && w2 === 0) { const v = varint(buf, p2); out.timestamp = v.num; return v.pos; }
        return undefined;
      });
      return m.end;
    }
    if (field === 2 && wire === 2 && want !== 'header') {
      const m = subMessage(buf, pos);
      parseEntity(buf, view, m.start, m.end, want, out);
      return m.end;
    }
    return undefined;
  });
  return out;
}

/** FeedEntity: id(1), is_deleted(2), trip_update(3), vehicle(4), alert(5). */
function parseEntity(buf, view, start, end, want, out) {
  fields(buf, start, end, (field, wire, pos) => {
    if (field === 4 && wire === 2 && want === 'vehicles') {
      const m = subMessage(buf, pos);
      const v = parseVehiclePosition(buf, view, m.start, m.end);
      if (v.lat !== null && v.lon !== null) out.vehicles.push(v);
      return m.end;
    }
    if (field === 3 && wire === 2 && want === 'updates') {
      const m = subMessage(buf, pos);
      const u = parseTripUpdate(buf, m.start, m.end);
      if (u.trip) out.updates.push(u);
      return m.end;
    }
    return undefined;
  });
}

/** VehiclePosition: trip(1), position(2), timestamp(5), vehicle(8). */
function parseVehiclePosition(buf, view, start, end) {
  const v = {
    trip: null, lat: null, lon: null, bearing: null,
    speed: null, timestamp: null, vehicleId: null,
  };
  fields(buf, start, end, (field, wire, pos) => {
    switch (field) {
      case 1: {
        const m = subMessage(buf, pos);
        v.trip = parseTripDescriptor(buf, m.start, m.end);
        return m.end;
      }
      case 2: {
        const m = subMessage(buf, pos);
        const p = parsePosition(buf, view, 0, m.start, m.end);
        v.lat = p.lat; v.lon = p.lon; v.bearing = p.bearing; v.speed = p.speed;
        return m.end;
      }
      case 5: {
        if (wire !== 0) return undefined;
        const t = varint(buf, pos);
        v.timestamp = t.num;
        return t.pos;
      }
      case 8: {
        const m = subMessage(buf, pos);
        v.vehicleId = parseVehicleDescriptor(buf, m.start, m.end);
        return m.end;
      }
      default: return undefined;
    }
  });
  return v;
}

/**
 * TripUpdate: trip(1), stop_time_update(2 rip.), timestamp(4), delay(5).
 *
 * Del ventaglio di StopTimeUpdate si tiene il PRIMO che porta un delay —
 * gli update partono dalla prossima fermata, quindi il primo e' "il ritardo
 * adesso". Ripiego sul delay complessivo del TripUpdate se nessuno ce l'ha.
 */
function parseTripUpdate(buf, start, end) {
  const u = { trip: null, delay: null, nextStopSeq: null, canceled: false };
  let firstStuDelay = null;
  let overallDelay = null;
  fields(buf, start, end, (field, wire, pos) => {
    switch (field) {
      case 1: {
        const m = subMessage(buf, pos);
        u.trip = parseTripDescriptor(buf, m.start, m.end);
        if (u.trip.schedRel === 3) u.canceled = true; // CANCELED
        return m.end;
      }
      case 2: {
        const m = subMessage(buf, pos);
        // StopTimeUpdate: stop_sequence(1), arrival(2), departure(3)
        let seq = null;
        let dep = null;
        let arr = null;
        fields(buf, m.start, m.end, (f2, w2, p2) => {
          if (f2 === 1 && w2 === 0) { const v = varint(buf, p2); seq = v.num; return v.pos; }
          if (f2 === 2 && w2 === 2) {
            const s = subMessage(buf, p2);
            arr = parseStopTimeEvent(buf, s.start, s.end);
            return s.end;
          }
          if (f2 === 3 && w2 === 2) {
            const s = subMessage(buf, p2);
            dep = parseStopTimeEvent(buf, s.start, s.end);
            return s.end;
          }
          return undefined;
        });
        if (firstStuDelay === null) {
          const d = (dep && dep.delay !== null) ? dep.delay
            : (arr && arr.delay !== null ? arr.delay : null);
          if (d !== null) {
            firstStuDelay = d;
            u.nextStopSeq = seq;
          }
        }
        return m.end;
      }
      case 5: {
        if (wire !== 0) return undefined;
        const v = varint(buf, pos);
        overallDelay = v.lo | 0;
        return v.pos;
      }
      default: return undefined;
    }
  });
  u.delay = firstStuDelay !== null ? firstStuDelay : overallDelay;
  return u;
}
