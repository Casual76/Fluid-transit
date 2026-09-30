import assert from 'node:assert/strict';
import { test, describe } from 'node:test';
import {
  refreshPolicy,
  holdEmptyTripUpdates,
  LAZY_REFRESH_AFTER_SECONDS,
  BLOCKING_REFRESH_AFTER_SECONDS,
  EMPTY_HOLD_SECONDS,
  BLOCKING_WAIT_MS,
  withDeadline,
} from '../src/freshness.js';

const NOW = 1_790_764_968;

describe('refreshPolicy', () => {
  test('uno snapshot giovane si serve com\'e\'', () => {
    assert.equal(refreshPolicy({ now: NOW, generatedAt: NOW - 20, lastRefreshAt: 0 }), 'none');
  });

  test('fra la soglia pigra e quella bloccante si rinfresca per chi viene dopo', () => {
    const generatedAt = NOW - (LAZY_REFRESH_AFTER_SECONDS + 5);
    assert.equal(refreshPolicy({ now: NOW, generatedAt, lastRefreshAt: 0 }), 'lazy');
  });

  // Il caso del 30/09: nessuno leggeva da due ore, e il primo lettore
  // riceveva le due ore. Deve aspettare il giro, non servire il vecchio.
  test('dopo una pausa lunga il primo lettore aspetta il giro', () => {
    assert.equal(
      refreshPolicy({ now: NOW, generatedAt: NOW - 7464, lastRefreshAt: 0 }),
      'blocking',
    );
  });

  test('la soglia bloccante e\' compresa', () => {
    const generatedAt = NOW - BLOCKING_REFRESH_AFTER_SECONDS;
    assert.equal(refreshPolicy({ now: NOW, generatedAt, lastRefreshAt: 0 }), 'blocking');
  });

  // Origine ferma: il giro trova gli stessi timestamp e non riscrive, quindi
  // l'eta' resta alta. Senza guardiano ogni lettura aspetterebbe un giro che
  // non cambia niente.
  test('un giro appena fatto disarma quello bloccante', () => {
    const s = { now: NOW, generatedAt: NOW - 600, lastRefreshAt: NOW - 30 };
    assert.equal(refreshPolicy(s), 'none');
  });

  test('un giro di un minuto fa lascia solo quello pigro', () => {
    const s = { now: NOW, generatedAt: NOW - 600, lastRefreshAt: NOW - 60 };
    assert.equal(refreshPolicy(s), 'lazy');
  });

  test('senza snapshot si aspetta il giro', () => {
    assert.equal(refreshPolicy({ now: NOW, generatedAt: 0, lastRefreshAt: 0 }), 'blocking');
  });
});

describe('holdEmptyTripUpdates', () => {
  const prevPieno = { generatedAt: NOW - 120, delayCount: 1762 };

  // Il caso del 30/09 a mezzogiorno: zero ritardi e nessuna data, fra due
  // feed pieni. Non deve cancellare i ritardi di tutti.
  test('un trip-updates senza data subito dopo uno pieno non si scrive', () => {
    assert.equal(holdEmptyTripUpdates({ now: NOW, tuTs: 0, prev: prevPieno }), true);
  });

  test('un trip-updates datato si scrive sempre, anche se vuoto', () => {
    assert.equal(holdEmptyTripUpdates({ now: NOW, tuTs: NOW - 30, prev: prevPieno }), false);
  });

  // La notte: vuoto e' la risposta giusta, e dopo cinque minuti si accetta.
  test('oltre la finestra un feed vuoto si accetta', () => {
    const vecchio = { generatedAt: NOW - EMPTY_HOLD_SECONDS, delayCount: 1762 };
    assert.equal(holdEmptyTripUpdates({ now: NOW, tuTs: 0, prev: vecchio }), false);
  });

  test('se anche prima era vuoto non c\'e\' niente da proteggere', () => {
    const vuoto = { generatedAt: NOW - 60, delayCount: 0 };
    assert.equal(holdEmptyTripUpdates({ now: NOW, tuTs: 0, prev: vuoto }), false);
    assert.equal(holdEmptyTripUpdates({ now: NOW, tuTs: 0, prev: null }), false);
  });
});

describe('withDeadline', () => {
  test('un giro puntuale porta il suo esito', async () => {
    const esito = await withDeadline(Promise.resolve('scritto: 1 veicoli'), 50);
    assert.equal(esito, 'scritto: 1 veicoli');
  });

  // L'origine appesa: il lettore non deve aspettare i dieci secondi del
  // fetch, ma servire quello che c'e' allo scadere del tetto.
  test('un giro che non finisce non trattiene la risposta', async () => {
    const appeso = new Promise(() => {});
    const inizio = Date.now();
    const esito = await withDeadline(appeso, 30);
    assert.equal(esito, undefined);
    assert.ok(Date.now() - inizio < 1000);
  });

  test('un giro fallito resta un fallimento', async () => {
    await assert.rejects(withDeadline(Promise.reject(new Error('origine giu\'')), 50), /origine/);
  });

  test('il tetto sta sotto il timeout di lettura dell\'app', () => {
    // RealtimeClient legge con 20 s di timeout, e all'avvio chiede quattro
    // sezioni insieme: il tetto deve lasciarne ampiamente.
    assert.ok(BLOCKING_WAIT_MS <= 5_000);
  });
});
