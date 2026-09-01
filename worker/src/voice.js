/**
 * Il mic evoluto: l'app manda l'audio, qui si trascrive (Whisper via Groq)
 * e si interpreta (LLM piccolo): "portami alle Cascine" e' un comando di
 * navigazione, "orari della 6" una ricerca, il resto e' dettatura.
 *
 * La chiave vive SOLO come secret del Worker (GROQ_API_KEY): mai nell'app,
 * mai nel repo. Senza chiave l'endpoint risponde 501 e l'app ricade sul
 * riconoscimento vocale di sistema, senza drammi.
 */

const GROQ = 'https://api.groq.com/openai/v1';

/** Tetto sull'audio: ~20 s di AAC a 24 kbps. Un comando, non un podcast. */
const MAX_AUDIO_BYTES = 600_000;

const SYSTEM_PROMPT = `Sei l'interprete vocale di un'app di trasporto pubblico toscana.
Ricevi la trascrizione di cio' che l'utente ha detto. Rispondi SOLO con un oggetto JSON:
{"azione": "naviga" | "cerca" | "detta", "testo": "..."}

- "naviga": l'utente vuole ANDARE in un posto ("portami a X", "come arrivo a X",
  "andiamo in piazza Y", "navigami verso Z"). In "testo" metti SOLO la destinazione,
  pulita ("alle Cascine" -> "Cascine").
- "cerca": l'utente nomina una fermata, una linea o un luogo da guardare
  ("fermata unita'", "linea 6", "orari del 23"). In "testo" il termine da cercare.
- "detta": tutto il resto. In "testo" la trascrizione cosi' com'e'.

Niente altro testo fuori dal JSON.`;

export async function serveVoice(request, env) {
  if (request.method !== 'POST') {
    return json({ ok: false, error: 'serve un POST con l\'audio' }, 405);
  }
  if (!env.GROQ_API_KEY) {
    // La chiave non e' configurata: l'app lo capisce e usa il mic di sistema.
    return json({ ok: false, error: 'chiave Groq non configurata' }, 501);
  }
  const audio = new Uint8Array(await request.arrayBuffer());
  if (audio.length < 200) return json({ ok: false, error: 'audio vuoto' }, 400);
  if (audio.length > MAX_AUDIO_BYTES) {
    return json({ ok: false, error: 'audio troppo lungo' }, 413);
  }

  // --- 1. la trascrizione --------------------------------------------------
  const form = new FormData();
  form.append('model', 'whisper-large-v3-turbo');
  form.append('language', 'it');
  form.append('temperature', '0');
  form.append(
    'file',
    new File([audio], 'comando.m4a', { type: request.headers.get('content-type') || 'audio/mp4' }),
  );
  const sttRes = await fetch(`${GROQ}/audio/transcriptions`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${env.GROQ_API_KEY}` },
    body: form,
  });
  if (!sttRes.ok) {
    return json({ ok: false, error: `trascrizione fallita (HTTP ${sttRes.status})` }, 502);
  }
  const transcript = ((await sttRes.json()).text || '').trim();
  if (!transcript) return json({ ok: false, error: 'non ho sentito niente' }, 422);

  // --- 2. l'interpretazione ------------------------------------------------
  const llmRes = await fetch(`${GROQ}/chat/completions`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${env.GROQ_API_KEY}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      model: 'llama-3.1-8b-instant',
      temperature: 0,
      max_tokens: 120,
      response_format: { type: 'json_object' },
      messages: [
        { role: 'system', content: SYSTEM_PROMPT },
        { role: 'user', content: transcript },
      ],
    }),
  });

  // L'LLM e' un di piu': se inciampa, la trascrizione vale comunque.
  let azione = 'detta';
  let testo = transcript;
  if (llmRes.ok) {
    try {
      const parsed = JSON.parse((await llmRes.json()).choices[0].message.content);
      if (['naviga', 'cerca', 'detta'].includes(parsed.azione) && parsed.testo) {
        azione = parsed.azione;
        testo = String(parsed.testo).trim() || transcript;
      }
    } catch {
      // JSON storto: si detta e basta.
    }
  }

  return json({ ok: true, azione, testo, trascrizione: transcript });
}

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json', 'cache-control': 'no-store' },
  });
}
