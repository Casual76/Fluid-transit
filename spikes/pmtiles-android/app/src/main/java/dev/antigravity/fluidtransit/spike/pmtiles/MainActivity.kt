package dev.antigravity.fluidtransit.spike.pmtiles

import android.graphics.Color
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.WellKnownTileServer
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.module.http.HttpRequestUtil
import java.util.concurrent.atomic.AtomicInteger

/**
 * Spike 2 della Fase 1.
 *
 * Una domanda sola: MapLibre Android apre un archivio `pmtiles://` remoto,
 * e lo fa leggendone pezzi con richieste Range invece di scaricarlo intero?
 * Il supporto e' dichiarato, ma un supporto dichiarato che scarichi 6 MB per
 * mostrare un isolato non sarebbe utilizzabile per una basemap regionale.
 *
 * Tutto cio' che serve a rispondere e' strumentato e finisce sia a schermo
 * sia in logcat sotto il tag [TAG], perche' la risposta va letta senza avere
 * il telefono in mano.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var report: TextView

    private val requestCount = AtomicInteger()
    private val rangeRequestCount = AtomicInteger()
    private val bytesDownloaded = java.util.concurrent.atomic.AtomicLong()
    private val hosts = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private var firstFrameAt = 0L
    private var styleLoadedAt = 0L
    private var startedAt = 0L
    private var featuresCounted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startedAt = System.currentTimeMillis()

        // MapLibre 11 pretende chiave e tile server anche quando non servono:
        // la basemap e' un PMTiles nostro, nessun servizio a chiave di mezzo.
        // La variante a un solo argomento lancia MapLibreConfigurationException.
        MapLibre.getInstance(this, null, WellKnownTileServer.MapLibre)

        // E va chiamata PRIMA di HttpRequestUtil, non dopo: setOkHttpClient
        // passa dal module provider, che a sua volta pretende l'istanza gia'
        // creata. Invertendo i due si ottiene la stessa eccezione di quando
        // getInstance manca del tutto, che indica il posto sbagliato.
        // Il client viene costruito a ogni richiesta, quindi installarlo qui
        // intercetta comunque anche la prima lettura dell'header PMTiles.
        HttpRequestUtil.setOkHttpClient(
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val range = request.header("Range")
                    requestCount.incrementAndGet()
                    if (range != null) rangeRequestCount.incrementAndGet()
                    hosts.merge(request.url.host, 1, Int::plus)
                    Log.i(TAG, "-> ${request.method} ${request.url} ${range ?: "(intero)"}")
                    val response = chain.proceed(request)
                    val length = response.header("Content-Length")?.toLongOrNull() ?: 0L
                    bytesDownloaded.addAndGet(length)
                    Log.i(
                        TAG,
                        "<- ${response.code} ${response.header("Content-Range") ?: ""} ${length}B",
                    )
                    response
                }
                .build()
        )

        mapView = MapView(this)
        mapView.onCreate(savedInstanceState)

        report = TextView(this).apply {
            setBackgroundColor(Color.parseColor("#CC0C0A12"))
            setTextColor(Color.parseColor("#EDE7F6"))
            textSize = 11f
            setPadding(24, 24, 24, 24)
            text = "avvio…"
        }

        val root = FrameLayout(this)
        root.addView(mapView)
        root.addView(
            report,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )
        setContentView(root)

        mapView.addOnDidFailLoadingMapListener { error ->
            Log.e(TAG, "ESITO: caricamento fallito -> $error")
            runOnUiThread { report.text = "FALLITO: $error" }
        }

        val styleJson = assets.open("style-firenze.json").bufferedReader().use { it.readText() }

        mapView.getMapAsync { map ->
            map.cameraPosition = CameraPosition.Builder()
                .target(FIRENZE)
                .zoom(14.0)
                .build()

            map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
                styleLoadedAt = System.currentTimeMillis()
                Log.i(TAG, "stile caricato in ${styleLoadedAt - startedAt} ms")
                Log.i(TAG, "sorgenti nello stile: ${style.sources.map { it.id }}")
                Log.i(TAG, "layer nello stile: ${style.layers.map { it.id }}")
            }

            // La SAM va nominata: MapLibre 11 espone due overload dello stesso
            // listener e una lambda nuda e' ambigua.
            mapView.addOnDidFinishRenderingFrameListener(
                MapView.OnDidFinishRenderingFrameListener { fully, encodingMs, renderingMs ->
                    if (firstFrameAt == 0L) {
                        firstFrameAt = System.currentTimeMillis()
                        Log.i(
                            TAG,
                            "primo frame in ${firstFrameAt - startedAt} ms " +
                                "(encoding $encodingMs ms, rendering $renderingMs ms)",
                        )
                    }
                    if (fully && !featuresCounted) countRenderedFeatures(map)
                    updateReport()
                }
            )
        }
    }

    /**
     * La prova che conta.
     *
     * Uno stile puo' caricarsi, la mappa puo' disegnarsi e le tile possono non
     * essere mai arrivate: si vedrebbe uno sfondo uniforme, che a occhio non
     * si distingue da una notte senza dati. Contare le geometrie effettivamente
     * disegnate dice se i vector tile dentro l'archivio sono stati letti,
     * decompressi e resi - cioe' se `pmtiles://` funziona davvero.
     */
    private fun countRenderedFeatures(map: MapLibreMap) {
        val viewport = RectF(0f, 0f, mapView.width.toFloat(), mapView.height.toFloat())
        if (viewport.width() <= 0 || viewport.height() <= 0) return
        val perLayer = LAYERS.associateWith { layer ->
            runCatching { map.queryRenderedFeatures(viewport, layer).size }.getOrDefault(-1)
        }
        val total = perLayer.values.filter { it > 0 }.sum()
        if (total == 0) return // ancora niente reso: si riprova al frame dopo
        featuresCounted = true
        renderedByLayer = perLayer
        Log.i(TAG, "ESITO: geometrie disegnate per layer -> $perLayer")
        Log.i(
            TAG,
            "ESITO: ${requestCount.get()} richieste, di cui ${rangeRequestCount.get()} con Range, " +
                "${bytesDownloaded.get()} byte scaricati su un archivio da $ARCHIVE_BYTES",
        )
        runOnUiThread { updateReport() }
    }

    private var renderedByLayer: Map<String, Int> = emptyMap()

    private fun updateReport() {
        val downloaded = bytesDownloaded.get()
        val share = if (ARCHIVE_BYTES > 0) 100.0 * downloaded / ARCHIVE_BYTES else 0.0
        report.text = buildString {
            appendLine("pmtiles:// remoto su MapLibre ${org.maplibre.android.BuildConfig.MAPLIBRE_VERSION_STRING}")
            appendLine("stile in ${styleLoadedAt - startedAt} ms · primo frame in ${firstFrameAt - startedAt} ms")
            appendLine(
                "${requestCount.get()} richieste · ${rangeRequestCount.get()} con Range · " +
                    "${downloaded / 1024} KB (${"%.1f".format(share)}% dell'archivio)"
            )
            appendLine("host: ${hosts.entries.joinToString { "${it.key}×${it.value}" }}")
            if (renderedByLayer.isEmpty()) {
                append("geometrie disegnate: ancora nessuna")
            } else {
                append("disegnate: " + renderedByLayer.filterValues { it > 0 }
                    .entries.joinToString { "${it.key}=${it.value}" })
            }
        }
    }

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { mapView.onPause(); super.onPause() }
    override fun onStop() { mapView.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onDestroy() { mapView.onDestroy(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    private companion object {
        const val TAG = "PmtilesSpike"
        val FIRENZE = LatLng(43.7672134, 11.2543435)
        val LAYERS = listOf("terra", "acqua", "strade", "edifici", "verde", "uso-suolo", "trasporto")

        /** Dimensione dell'archivio remoto: serve solo a dare senso alla percentuale. */
        const val ARCHIVE_BYTES = 6_601_156L
    }
}
