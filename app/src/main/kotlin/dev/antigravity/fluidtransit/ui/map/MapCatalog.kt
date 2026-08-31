package dev.antigravity.fluidtransit.ui.map

/**
 * Le sorgenti della mappa e le due modalita' decise: Stradale e Ibrida.
 *
 * Tutto cio' che riguarda "da dove vengono le tile" sta qui, perche' la
 * basemap e' infrastruttura di qualcun altro (OpenFreeMap, a donazioni,
 * senza garanzie): il giorno in cui andasse cambiata dev'essere una modifica
 * in questo file, non una caccia al tesoro nella UI.
 *
 * Discende dal banco di prova di Fase 1; le modalita' Satellite pura, Minima
 * e Scura non esistono piu' come scelte — il tema scuro e' una conseguenza
 * del tema di sistema, non una modalita' di mappa.
 */
object MapCatalog {

    enum class MapMode { STREETS, HYBRID }

    private const val OFM_STYLES = "https://tiles.openfreemap.org/styles"
    const val OFM_TILEJSON = "https://tiles.openfreemap.org/planet"
    const val OFM_GLYPHS = "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf"

    /**
     * Ortofoto della Regione Toscana, WMS GEOscopio. `rt_ofc.10k13` (volo
     * 2013, 1:10.000): l'unica annata a copertura totale — le 20 cm del
     * 2015-2017 sono parziali e su Firenze danno riquadri vuoti.
     */
    private const val RT_ORTOFOTO_TILES =
        "https://www502.regione.toscana.it/wmsraster/com.rt.wms.RTmap/wms" +
            "?map=wmsofc&SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap" +
            "&LAYERS=rt_ofc.10k13&STYLES=&CRS=EPSG:3857" +
            "&BBOX={bbox-epsg-3857}&WIDTH=512&HEIGHT=512" +
            "&FORMAT=image/jpeg&TRANSPARENT=false"

    /**
     * L'attribuzione e' dichiarata nella sorgente, non solo scritta in una
     * schermata: cosi' il controllo nativo di MapLibre la mostra sempre, ed
     * e' un obbligo di licenza, non una cortesia. OpenFreeMap chiede questo
     * testo esatto per i client non-GL-JS.
     */
    const val ATTR_OSM = "OpenFreeMap © OpenMapTiles Data from OpenStreetMap"
    const val ATTR_RT = "Ortofoto © Regione Toscana — SIPT"

    /**
     * Lo stile della modalita' corrente. La Stradale segue il tema: `liberty`
     * di giorno, `dark` di notte — la modalita' non cambia, cambia lo stile
     * sotto, com'e' stato deciso.
     */
    fun styleUri(mode: MapMode, darkTheme: Boolean): String? = when (mode) {
        MapMode.STREETS -> if (darkTheme) "$OFM_STYLES/dark" else "$OFM_STYLES/liberty"
        MapMode.HYBRID -> null // composto da noi: styleJson
    }

    fun styleJson(mode: MapMode): String = when (mode) {
        MapMode.HYBRID -> HYBRID_STYLE
        else -> error("la modalita' $mode ha uno stile per URL")
    }

    /**
     * Ortofoto piu' il minimo indispensabile di vettoriale sopra: strade
     * principali e nomi. Su una foto aerea le strade minori si vedono gia';
     * le etichette hanno un alone spesso perche' il fondo e' imprevedibile.
     * Niente edifici 3D qui: sopra una foto sono volumi inventati.
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
          "attribution": "$ATTR_OSM"
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

    // --- overlay della rete (il PMTiles nostro) ----------------------------

    const val OVERLAY_SOURCE = "ft-rete"
    const val LAYER_LINEE_EXTRA = "ft-linee-extra"
    const val LAYER_LINEE_URBANE = "ft-linee-urbane"
    const val LAYER_LINEA_SEL = "ft-linea-selezionata"
    const val LAYER_FERMATE = "ft-fermate"
    const val LAYER_FERMATE_NOMI = "ft-fermate-nomi"

    /**
     * Gli stadi di zoom decisi: niente sotto, e le tratte extraurbane —
     * lunghe, da guardare da lontano — compaiono prima delle urbane.
     */
    const val LINEE_EXTRA_MIN_ZOOM = 10.2f
    const val LINEE_URBANE_MIN_ZOOM = 12.2f
    const val FERMATE_MIN_ZOOM = 13.8f
    const val NOMI_MIN_ZOOM = 16.2f

    /** L'inclinazione fissa della navigazione/bussola. Non e' una preferenza. */
    const val NAV_TILT = 55.0
    const val NAV_ZOOM = 16.5

    /** Inquadratura di partenza: la Toscana intera. */
    const val HOME_LAT = 43.35
    const val HOME_LON = 11.0
    const val HOME_ZOOM = 7.6
}
