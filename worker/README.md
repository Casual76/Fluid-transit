# worker/ — Cloudflare Worker

## Stato: probe di Fase 1

Oggi questo pacchetto contiene **solo** il Worker di probe dello spike 1. Non ha
binding, non ha cron, non ha stato e non scrive su R2: serve a rispondere alla
domanda da cui dipende tutto il resto del design del proxy —

> l'origine `regionetoscana.smartregion.toscana.it` risponde anche quando la
> richiesta parte dalla rete Cloudflare, o filtra gli IP datacenter?

Se la risposta fosse "filtra", il proxy descritto nel piano non sarebbe
realizzabile e andrebbe scelto un piano B (cron sulla GitHub Action, oppure
app che parla direttamente all'origine) **prima** di scrivere codice di
prodotto. Per questo lo spike viene per primo.

In Fase 4 lo stesso pacchetto diventa il proxy vero: cron al minuto, decoder
protobuf statico, slicing in celle 0,25°, un solo oggetto R2 `rt/latest.bin`,
Durable Object `RtHub` per lo storico. La probe verra' rimossa o ridotta a
`/rt/v1/health`.

## Endpoint

| endpoint | domanda a cui risponde |
|---|---|
| `/probe` | i tre feed RT rispondono? i byte sono un FeedMessage valido? quanto e' vecchio? |
| `/probe?feed=alerts` | come sopra, un feed solo |
| `/probe/conditional` | `ETag` / `Last-Modified` sono utilizzabili, o ogni fetch resta integrale? |
| `/probe/burst?n=8` | una raffica ravvicinata viene rate-limitata? ogni quanto cambia davvero il feed? |
| `/probe/static` | lo zip GTFS da 129 MB supporta le richieste `Range`? |
| `/raw?feed=…` | passthrough dei byte grezzi, per ispezionarli in locale |

Ogni fetch verso l'origine scavalca esplicitamente la cache di Cloudflare
(`cf.cacheTtlByStatus` negativo): la probe deve misurare l'origine, non l'edge.

## Deploy

In locale non c'e' Node, quindi nessun `wrangler` sulla macchina di sviluppo:
il deploy passa dalla Action [`deploy-worker.yml`](../.github/workflows/deploy-worker.yml),
che usa i secret `CLOUDFLARE_API_TOKEN` e `CLOUDFLARE_ACCOUNT_ID` gia'
configurati in Fase 0. Parte da sola a ogni push che tocca `worker/`, oppure a
mano da `workflow_dispatch`.

URL: `https://fluid-transit-probe.fluid-transit.workers.dev`

## Attribuzione

I dati provengono da Regione Toscana / Autolinee Toscane e sono distribuiti in
**CC-BY 4.0**. Ogni risposta della probe porta l'header `x-data-source` con
l'attribuzione e la dichiarazione che i dati sono modificati — obbligo che vale
anche per il proxy della Fase 4, dove la trasformazione e' sostanziale.
