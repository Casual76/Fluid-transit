/**
 * Quando una lettura deve rinfrescare il proxy, e se deve aspettarlo.
 *
 * Il proxy non ha un metronomo. Il Cron Trigger di Cloudflare e' stato tolto
 * il 30/09/2026 perche' sul piano gratuito non poteva funzionare: concede
 * 10 ms di CPU per invocazione, e un giro vero — parse dei trip-updates,
 * snapshot, previsioni per fermata — ne costa 70-90 (misurato sui feed veri).
 * Il cron partiva ogni minuto, scaricava 1,25 MB dalla Regione, trovava il
 * feed cambiato e veniva ucciso a meta' del parse: per questo i battiti che
 * riusciva a scrivere dicevano sempre "invariato", l'unico giro che ci sta
 * in 10 ms.
 *
 * Quindi il proxy si tiene fresco da chi lo legge, e questa funzione decide
 * come. Sta qui, da sola e senza I/O, perche' e' la parte che si puo'
 * sbagliare in silenzio: `index.js` usa `caches` e R2, e non ha test.
 */

/**
 * Oltre questa eta' dello snapshot una lettura avvia un giro in background:
 * chi legge adesso riceve quello che c'e', chi viene dopo il dato nuovo. Con
 * l'app che polla ogni 30 s, basta un utente perche' il proxy resti fresco.
 */
export const LAZY_REFRESH_AFTER_SECONDS = 55;

/**
 * Oltre questa eta' non si serve il vecchio rinfrescando per il PROSSIMO: si
 * rinfresca PRIMA di rispondere.
 *
 * Il motivo, misurato: senza un cron, dopo una pausa lo snapshot ha l'eta'
 * della pausa. Il 30/09 alle 12:40 aveva 7.464 secondi, e il primo lettore
 * riceveva quelli; con un utente solo, il "dopo" che avrebbe visto il dato
 * nuovo non arrivava prima di un giro di poll. E' lo stesso sintomo che
 * faceva scendere l'app sulla sorgente diretta ("Ritardi non disponibili")
 * dopo tre letture vecchie di fila. Aspettare un secondo e' meglio che
 * mostrare due ore di ritardo.
 */
export const BLOCKING_REFRESH_AFTER_SECONDS = 90;

/**
 * @param {{ now: number, generatedAt: number, lastRefreshAt: number }} s
 *   `generatedAt` e' quando lo snapshot e' stato SCRITTO l'ultima volta;
 *   `lastRefreshAt` quando questo isolate ha interrogato l'origine.
 * @returns {'none' | 'lazy' | 'blocking'}
 *
 * Il guardiano `lastRefreshAt` serve al caso in cui l'origine e' ferma: un
 * giro che trova gli stessi timestamp non riscrive niente, quindi
 * `generatedAt` non avanza e l'eta' resta sopra soglia. Senza guardiano ogni
 * lettura in quella finestra rifarebbe tre fetch e, peggio, aspetterebbe un
 * giro che non cambia niente.
 */
export function refreshPolicy({ now, generatedAt, lastRefreshAt }) {
  const age = now - (generatedAt || 0);
  const sinceLast = now - (lastRefreshAt || 0);
  if (age >= BLOCKING_REFRESH_AFTER_SECONDS && sinceLast >= BLOCKING_REFRESH_AFTER_SECONDS) {
    return 'blocking';
  }
  if (age >= LAZY_REFRESH_AFTER_SECONDS && sinceLast >= LAZY_REFRESH_AFTER_SECONDS) {
    return 'lazy';
  }
  return 'none';
}

/**
 * Per quanto un trip-updates senza timestamp non prende il posto di uno pieno.
 *
 * L'origine a volte risponde con un trip-updates vuoto e senza data: il
 * 30/09/2026 alle 12:40 lo snapshot servito aveva zero ritardi e
 * `updates.feedAgeSeconds` nullo, in pieno giorno, mentre veicoli e avvisi
 * erano normali. Scriverlo voleva dire togliere tutti i ritardi a tutti fino
 * al giro dopo — e da quando la lettura aspetta il giro, lo avrebbe ricevuto
 * proprio chi aspettava.
 *
 * La finestra e' corta apposta: oltre cinque minuti un feed vuoto si accetta,
 * perche' a notte fonda vuoto e' la risposta giusta, e congelare i ritardi
 * della sera fino al mattino sarebbe peggio del buco.
 */
export const EMPTY_HOLD_SECONDS = 300;

/**
 * @param {{ now: number, tuTs: number, prev: { generatedAt: number, delayCount: number } | null }} s
 * @returns {boolean} true = non scrivere, tenere lo snapshot di prima.
 */
export function holdEmptyTripUpdates({ now, tuTs, prev }) {
  if (tuTs) return false;
  if (!prev || !(prev.delayCount > 0)) return false;
  return now - (prev.generatedAt || 0) < EMPTY_HOLD_SECONDS;
}

/**
 * Quanto una lettura aspetta il giro bloccante prima di servire il vecchio.
 *
 * Il giro dura di solito uno-tre secondi (tre fetch da 170-400 ms, il parse,
 * le scritture su R2); il limite dei fetch verso l'origine e' invece dieci
 * secondi per feed, perche' un giro lento e' meglio di nessun giro. Ma quei
 * dieci secondi non devono diventare l'attesa del telefono: quando l'origine
 * della Regione si pianta — e succede, il keepalive del 24/09 e' rimasto
 * appeso un minuto — ogni isolate nuovo avrebbe fatto aspettare al suo primo
 * lettore tutto il timeout, con quattro sezioni chieste in parallelo
 * all'avvio. Prima del rinfresco alla lettura quel lettore riceveva subito
 * il dato vecchio con la sua eta'; con questo tetto lo riceve dopo quattro
 * secondi, e il giro finisce comunque in background per chi viene dopo.
 */
export const BLOCKING_WAIT_MS = 4_000;

/**
 * Aspetta `promise` al massimo `ms` millisecondi.
 *
 * @template T
 * @param {Promise<T>} promise
 * @param {number} ms
 * @returns {Promise<T | undefined>} l'esito, o undefined se e' scaduto il tempo.
 *   Un rifiuto passa com'e': chi chiama decide cosa farne.
 */
export async function withDeadline(promise, ms) {
  let timer;
  const deadline = new Promise((resolve) => {
    timer = setTimeout(() => resolve(undefined), ms);
  });
  try {
    return await Promise.race([promise, deadline]);
  } finally {
    clearTimeout(timer);
  }
}
