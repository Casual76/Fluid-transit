# worker/ — il proxy realtime (Cloudflare Worker)

## Stato: proxy di Fase 4 (`fluid-transit-rt`)

La probe di Fase 1 ha fatto il suo lavoro (l'origine risponde anche dagli IP
Cloudflare) ed e' stata sostituita da questo Worker. Il principio di design,
misurato in Fase 1, e':

- **l'origine non manda validatori e non supporta gzip**: ogni fetch e'
  integrale, quindi lo fa il proxy — mai il client — e lo fa quando una
  lettura trova lo snapshot vecchio (`src/freshness.js`);
- **l'origine si rigenera ogni ~2 minuti**: quando i timestamp non cambiano
  il giro NON riscrive lo snapshot (meta' delle scritture R2 risparmiate);
- **il parse si fa una volta per generazione, non per richiesta**: una
  richiesta normale serve byte gia' pronti, affettati e cacheati 35 s
  sull'edge — cinque secondi piu' del poll dell'app, che chiede ogni 30 s: a
  45 (e poi a 25) la voce non arrivava mai viva al giro successivo. Solo la
  richiesta che trova lo snapshot vecchio fa il giro: in sottofondo oltre i
  55 secondi, aspettandolo oltre i 90.

**Non c'e' un Cron Trigger** (`crons = []` in `wrangler.toml`). Sul piano
gratuito un'invocazione cron ha 10 ms di CPU e un giro ne costa 70-90
(misurato il 30/09/2026): partiva ogni minuto, scaricava i feed e moriva nel
parse. Sulle richieste HTTP lo stesso limite oggi e' tollerato; se smettesse
di esserlo, la via e' Workers Paid (vedi `APERTO.md`).

## Architettura

```
richiesta:   Cache API (35 s) → miss → R2 rt/<sezione>.gz → risposta
             se lo snapshot ha piu' di 55 s: giro in sottofondo
             se ne ha piu' di 90: giro PRIMA di rispondere (freshness.js)
giro:        origine (3 feed GTFS-RT) → decoder protobuf statico (gtfsrt.js)
             → snapshot binario compatto (snapshot.js) → R2 rt/latest.bin
             → sezioni gia' affettate e compresse → R2 rt/*.gz
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
| `/rt/v1/alerts` | il FeedMessage alerts grezzo | protobuf (Oggi e assistente) |
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
