// Real safety tool, not a decorative view: shows the user's actual live GPS position on a real
// street-level Mapbox map, so someone genuinely lost can see exactly where they are and get
// oriented, the same way opening Google Maps would. Token passed as a URL query param from
// Kotlin (BuildConfig.MAPBOX_TOKEN, itself from local.properties - never hardcoded here, same
// reasoning as the rest of this app's Mapbox usage).
const params = new URLSearchParams(window.location.search);
mapboxgl.accessToken = params.get('token') || '';

// Satellite Streets (real aerial imagery + Mapbox's standard building/road vector data), not the
// flat dark-v11 style - user asked to actually see their house/place, not just a dot on an
// abstract street diagram. Pitched by default so 3D buildings (added below) read as three-
// dimensional rather than flat - a top-down camera makes extruded buildings look the same as 2D.
const map = new mapboxgl.Map({
    container: 'map',
    style: 'mapbox://styles/mapbox/satellite-streets-v12',
    center: [0, 0],
    zoom: 2,
    pitch: 45,
    attributionControl: true
});

// Real 3D building extrusion - Mapbox's composite source's "building" layer already carries real
// height/min_height data, this just renders it as extruded volumes instead of flat footprints.
// Only visible once zoomed in close (min zoom 15), same as Mapbox's own reference examples - at
// wider zooms this would just be visual noise.
map.on('load', () => {
    const layers = map.getStyle().layers;
    const labelLayerId = layers.find((layer) => layer.type === 'symbol' && layer.layout && layer.layout['text-field'])?.id;
    map.addLayer(
        {
            id: '3d-buildings',
            source: 'composite',
            'source-layer': 'building',
            filter: ['==', 'extrude', 'true'],
            type: 'fill-extrusion',
            minzoom: 15,
            paint: {
                'fill-extrusion-color': '#8a8a8a',
                'fill-extrusion-height': ['get', 'height'],
                'fill-extrusion-base': ['get', 'min_height'],
                'fill-extrusion-opacity': 0.85
            }
        },
        labelLayerId
    );
});

let marker = null;
let hasCenteredOnce = false;
let userHasPanned = false;
let lastLat = null;
let lastLng = null;

map.on('dragstart', () => { userHasPanned = true; });

// Real fix data pushed repeatedly from Kotlin's LocationManager updates (see MyLocationActivity)
// - not a one-shot snapshot, a live feed for as long as this screen is open.
window.updateMyLocation = function (lat, lng, accuracyMeters) {
    lastLat = lat;
    lastLng = lng;

    if (!marker) {
        const el = document.createElement('div');
        el.className = 'my-location-dot';
        marker = new mapboxgl.Marker({ element: el }).setLngLat([lng, lat]).addTo(map);
    } else {
        marker.setLngLat([lng, lat]);
    }

    if (!hasCenteredOnce) {
        hasCenteredOnce = true;
        // z17 is close enough for real building shapes/the 3D layer above (minzoom 15) to
        // actually show, not just roads.
        map.flyTo({ center: [lng, lat], zoom: 17, pitch: 55 });
    } else if (!userHasPanned) {
        // Keep following the live position until the user deliberately looks elsewhere -
        // RECENTER below brings it back.
        map.easeTo({ center: [lng, lat], duration: 800 });
    }

    const accuracyText = accuracyMeters != null ? `±${Math.round(accuracyMeters)}m` : 'unknown';
    document.getElementById('status-panel').textContent =
        `Lat: ${lat.toFixed(6)}  Lng: ${lng.toFixed(6)}\nAccuracy: ${accuracyText}`;
};

window.setLocationStatus = function (text) {
    document.getElementById('status-panel').textContent = text;
};

document.getElementById('recenter-btn').addEventListener('click', () => {
    userHasPanned = false;
    if (lastLat != null && lastLng != null) {
        map.flyTo({ center: [lastLng, lastLat], zoom: 17, pitch: 55 });
    }
});
