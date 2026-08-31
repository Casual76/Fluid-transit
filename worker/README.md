# worker/ — il proxy realtime (Cloudflare Worker)

## Stato: proxy di Fase 4 (`fluid-transit-rt`)

La probe di Fase 1 ha fatto il suo lavoro (l'origine risponde anche dagli IP
Cloudflare) ed e' stata sostituita da questo Worker. Il principio di design,
misurato in Fase 1, e':

- **l'origine non manda validatori e non supporta gzip**: ogni fetch e'
  integrale, quindi lo fa il cron una volta al minuto — mai il client;
- **l'origine si rigenera ogni ~2 minuti**: quando i timestamp non cambiano
  il cron NON riscrive lo snapshot (meta' delle scritture R2 risparmiate);
- **zero parsing sul percorso richiesta**: le richieste servono byte gia'
  pronti, affettati dallo snapshot e cacheati 45 s sull'edge.

## Architettura

```
cron 1/min:  origine (3 feed GTFS-RT) → decoder protobuf statico (gtfsrt.js)
             → snapshot binario compatto (snapshot.js) → R2 rt/latest.bin
richiesta:   Cache API (45 s) → miss → R2 → slice + gzip → risposta
```

Gli id del feed viaggiano come **hash FNV-1a a 64 bit, identici a quelli del
bundle** (`Ftb.hash64`): l'app risolve corse e linee via `TRIP_ID_INDEX` e
`routeIdHash` senza portarsi dietro le stringhe. Il formato dei record e'
documentato in testa a [`src/snapshot.js`](src/snapshot.js).

## Endpoint

| endpoint | contenuto | formato |
|---|---|---|
| `/rt/v1/vehicles` | posizioni dei veicoli | mini-header 24 B + record da 40 B |
| `/rt/v1/updates` | ritardi per corsa | mini-header 24 B + record da 32 B |
| `/rt/v1/alerts` | il FeedMessage alerts grezzo | protobuf (per Fase 6/8) |
| `/rt/v1/health` | stato del proxy in JSON | per Stato dei dati e debug |

Tutte le risposte binarie sono **gzip incondizionato** (`Content-Encoding:
gzip`, `encodeBody: manual`): OkHttp le decomprime da solo; con curl serve
`--compressed` o un `gunzip` a valle. Ogni risposta porta `X-Feed-Age`
calcolato sul timestamp **dell'origine**, non del poll, ed `ETag` per i 304.

URL: `https://fluid-transit-rt.fluid-transit.workers.dev`

## Deploy

In locale non c'e' Node, quindi nessun `wrangler` sulla macchina di sviluppo:
il deploy passa dalla Action [`deploy-worker.yml`](../.github/workflows/deploy-worker.yml),
che usa i secret `CLOUDFLARE_API_TOKEN` e `CLOUDFLARE_ACCOUNT_ID` gia'
configurati in Fase 0. Parte da sola a ogni push che tocca `worker/`, oppure a
mano da `workflow_dispatch`.

Il vecchio Worker `fluid-transit-probe` resta deployato su Cloudflare ma e'
inerte (nessun cron, nessun binding, zero costi): si puo' eliminare dalla
dashboard quando si vuole.

## Attribuzione

I dati provengono da Regione Toscana / Autolinee Toscane e sono distribuiti in
**CC-BY 4.0**. Ogni risposta porta l'header `x-data-source` con l'attribuzione
e la dichiarazione che i dati sono modificati — qui la trasformazione e'
sostanziale (aggregazione, ricodifica binaria, hashing degli id).
