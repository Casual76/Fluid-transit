package dev.antigravity.fluidtransit.spike.map

/**
 * Le sorgenti della mappa e le modalità di visualizzazione.
 *
 * ⚠️ **BANCO DI PROVA, NON FUNZIONALITÀ.** Questo modulo serviva a rispondere a
 * una domanda sola — le sorgenti scelte funzionano davvero su un telefono? — e
 * la risposta è sì. Non è un pezzo di app scritto in anticipo.
 *
 * Quello che sopravvive alla Fase 3 è la *forma*: tutto ciò che riguarda "da
 * dove vengono le tile" sta in un file solo, perché la basemap è infrastruttura
 * di qualcun altro e il giorno in cui andasse cambiata dev'essere una modifica
 * qui e non una caccia al tesoro dentro la UI.
 *
 * Quello che **non** sopravvive sono le scelte: quante modalità offrire e con
 * che nomi, quale sia quella di partenza, se il satellite sia attivo di
 * default. Nessuna di queste è stata decisa — sono messe lì per poter essere
 * guardate. Vanno chieste prima di scrivere l'app, non ereditate da qui.
 */
object MapCatalog {

    // ---------------------------------------------------------------- OSM

    /**
     * Stili pronti di OpenFreeMap. Sono già scritti sullo schema OpenMapTiles
     * che il loro server produce, quindi non c'è niente da riscrivere: si
     * caricano per URL.
     */
    private const val OFM_STYLES = "https://tiles.openfreemap.org/styles"

    /** TileJSON del planet. Serve alle modalità che compongono uno stile proprio. */
    const val OFM_TILEJSON = "https://tiles.openfreemap.org/planet"

    const val OFM_GLYPHS = "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf"

    // ------------------------------------------------------------ ortofoto

    /**
     * Ortofoto della Regione Toscana, servizio WMS GEOscopio.
     *
     * `rt_ofc.10k13` e non una delle annate a 20 cm: quelle sono dichiarate a
     * "copertura del territorio: parziale" e sopra Firenze non ci sono. Il
     * volo 2013 a 1:10.000 copre invece l'intera regione, isole comprese —
     * verificato renderizzando la Toscana intera in un colpo solo. Meglio una
     * copertura uniforme e più vecchia che buchi bianchi a caso.
     *
     * Il layer di gruppo `rt_ofc` sarebbe più recente dove può, ma disegna
     * sopra la griglia dei quadri di unione: inservibile come basemap.
     *
     * `{bbox-epsg-3857}` lo sostituisce MapLibre. WMS 1.3.0 con EPSG:3857 ha
     * l'ordine degli assi est-nord, quindi il bbox va passato così com'è.
     */
    private const val RT_ORTOFOTO_TILES =
        "https://www502.regione.toscana.it/wmsraster/com.rt.wms.RTmap/wms" +
            "?map=wmsofc&SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap" +
            "&LAYERS=rt_ofc.10k13&STYLES=&CRS=EPSG:3857" +
            "&BBOX={bbox-epsg-3857}&WIDTH=512&HEIGHT=512" +
            "&FORMAT=image/jpeg&TRANSPARENT=false"

    // --------------------------------------------------------- attribuzioni

    const val ATTR_OSM = "© OpenStreetMap contributors · OpenFreeMap"
    const val ATTR_RT = "Ortofoto © Regione Toscana — SIPT"

    /**
     * L'attribuzione va dichiarata nella sorgente, non solo scritta nella UI:
     * così il controllo di MapLibre la mostra anche quando la nostra
     * schermata cambia, ed è un obbligo di licenza, non una cortesia.
     */
    private const val ATTR_OSM_JSON = "© OpenStreetMap contributors · OpenFreeMap"

    // ------------------------------------------------------------- modalità

    enum class MapMode(val label: String, val hint: String) {
        STREETS(
            "Stradale",
            "OpenFreeMap Liberty: colori pieni, tutte le etichette, edifici 3D già nello stile",
        ),
        MINIMAL(
            "Minima",
            "Positron: quasi bianca. È la base su cui le nostre linee si vedono meglio",
        ),
        DARK(
            "Scura",
            "La stessa mappa in notturna, per capire come starebbe il vetro sopra",
        ),
        SATELLITE(
            "Satellite",
            "Solo ortofoto della Regione. Nessuna etichetta: si vede il territorio, non i nomi",
        ),
        HYBRID(
            "Ibrida",
            "Ortofoto con strade e nomi sopra. È la modalità che la gente si aspetta da «satellite»",
        ),
    }

    /**
     * Stile per URL, quando esiste già pronto. `null` significa che lo stile
     * va composto da noi e arriva da [styleJson].
     */
    fun styleUri(mode: MapMode): String? = when (mode) {
        MapMode.STREETS -> "$OFM_STYLES/liberty"
        MapMode.MINIMAL -> "$OFM_STYLES/positron"
        MapMode.DARK -> "$OFM_STYLES/dark"
        MapMode.SATELLITE, MapMode.HYBRID -> null
    }

    fun styleJson(mode: MapMode): String = when (mode) {
        MapMode.SATELLITE -> SATELLITE_STYLE
        MapMode.HYBRID -> HYBRID_STYLE
        else -> error("la modalità $mode ha uno stile per URL")
    }

    /** Sola ortofoto: nessun vettoriale, quindi nessuna etichetta e nessun 3D. */
    private val SATELLITE_STYLE = """
    {
      "version": 8,
      "name": "Fluid Transit — satellite",
      "sources": {
        "ortofoto": {
          "type": "raster",
          "tiles": ["$RT_ORTOFOTO_TILES"],
          "tileSize": 512,
          "minzoom": 6,
          "maxzoom": 19,
          "attribution": "$ATTR_RT"
        }
      },
      "layers": [
        { "id": "sfondo", "type": "background", "paint": { "background-color": "#101014" } },
        { "id": "ortofoto", "type": "raster", "source": "ortofoto" }
      ]
    }
    """.trimIndent()

    /**
     * Ortofoto più il minimo indispensabile di vettoriale sopra: strade e nomi.
     *
     * Solo le classi di strada che servono a orientarsi, non tutte: su una
     * foto aerea le strade minori si vedono già, e ridisegnarle sopra fa
     * pasticcio. Le etichette hanno un alone spesso perché il fondo sotto è
     * imprevedibile — bianco su tetti chiari sparisce.
     *
     * La sorgente vettoriale resta disponibile anche per gli edifici estrusi.
     *
     * ⚠️ Nell'app **gli edifici 3D non vanno offerti in questa modalità**:
     * sopra una foto aerea sono volumi di un colore inventato che coprono la
     * foto, e senza fotogrammetria non c'è alcuna texture da metterci. Qui
     * l'interruttore resta acceso apposta, perché il punto del banco di prova
     * era proprio vedere che effetto fa.
     */
    private val HYBRID_STYLE = """
    {
      "version": 8,
      "name": "Fluid Transit — ibrida",
      "glyphs": "$OFM_GLYPHS",
      "sources": {
        "ortofoto": {
          "type": "raster",
          "tiles": ["$RT_ORTOFOTO_TILES"],
          "tileSize": 512,
          "minzoom": 6,
          "maxzoom": 19,
          "attribution": "$ATTR_RT"
        },
        "openmaptiles": {
          "type": "vector",
          "url": "$OFM_TILEJSON",
          "attribution": "$ATTR_OSM_JSON"
        }
      },
      "layers": [
        { "id": "sfondo", "type": "background", "paint": { "background-color": "#101014" } },
        { "id": "ortofoto", "type": "raster", "source": "ortofoto" },
        {
          "id": "strade-bordo",
          "type": "line",
          "source": "openmaptiles",
          "source-layer": "transportation",
          "minzoom": 11,
          "filter": ["in", ["get", "class"], ["literal", ["motorway", "trunk", "primary", "secondary"]]],
          "layout": { "line-cap": "round", "line-join": "round" },
          "paint": {
            "line-color": "#1b1b22",
            "line-opacity": 0.55,
            "line-width": ["interpolate", ["linear"], ["zoom"], 11, 3.0, 16, 10.0]
          }
        },
        {
          "id": "strade",
          "type": "line",
          "source": "openmaptiles",
          "source-layer": "transportation",
          "minzoom": 11,
          "filter": ["in", ["get", "class"], ["literal", ["motorway", "trunk", "primary", "secondary"]]],
          "layout": { "line-cap": "round", "line-join": "round" },
          "paint": {
            "line-color": "#f2ead9",
            "line-opacity": 0.75,
            "line-width": ["interpolate", ["linear"], ["zoom"], 11, 1.2, 16, 5.0]
          }
        },
        {
          "id": "etichette-strade",
          "type": "symbol",
          "source": "openmaptiles",
          "source-layer": "transportation_name",
          "minzoom": 14,
          "layout": {
            "symbol-placement": "line",
            "text-field": ["get", "name"],
            "text-font": ["Noto Sans Regular"],
            "text-size": 11,
            "text-max-angle": 30
          },
          "paint": {
            "text-color": "#ffffff",
            "text-halo-color": "#000000",
            "text-halo-width": 1.6,
            "text-halo-blur": 0.4
          }
        },
        {
          "id": "etichette-luoghi",
          "type": "symbol",
          "source": "openmaptiles",
          "source-layer": "place",
          "layout": {
            "text-field": ["get", "name"],
            "text-font": ["Noto Sans Bold"],
            "text-size": ["interpolate", ["linear"], ["zoom"], 6, 11, 14, 17],
            "text-anchor": "center"
          },
          "paint": {
            "text-color": "#ffffff",
            "text-halo-color": "#000000",
            "text-halo-width": 2.0,
            "text-halo-blur": 0.5
          }
        }
      ]
    }
    """.trimIndent()

    /**
     * Il layer degli edifici estrusi negli stili pronti di OpenFreeMap.
     * Esiste già: non va aggiunto, va acceso.
     */
    const val OFM_BUILDING_3D_LAYER = "building-3d"

    /** Quello che aggiungiamo noi dove non c'è, cioè in modalità ibrida. */
    const val OWN_BUILDING_3D_LAYER = "ft-edifici-3d"

    /** Sorgente vettoriale negli stili OpenMapTiles: serve per aggiungere il 3D. */
    const val VECTOR_SOURCE = "openmaptiles"

    /** Sorgente raster delle ortofoto, negli stili che ne hanno una. */
    const val RASTER_SOURCE = "ortofoto"

    const val BUILDING_SOURCE_LAYER = "building"

    /**
     * Sotto zoom 14 gli edifici non sono nemmeno nelle tile: accendere il 3D
     * più in là serve solo a far lavorare la GPU a vuoto.
     */
    const val BUILDING_MIN_ZOOM = 14f
}
