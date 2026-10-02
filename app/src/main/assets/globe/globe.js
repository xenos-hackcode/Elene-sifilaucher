import * as THREE from './vendor/three.module.js';
import { OrbitControls } from './vendor/OrbitControls.js';

// Real 3D rotatable Earth - Three.js/WebGL rendered inside this WebView, not a screenshot or a
// hosted map SDK. OrbitControls orbits the CAMERA around a stationary globe (not the globe
// itself) - simpler math for the double-click raycast below, and functionally identical from the
// user's point of view (drag to rotate, pinch/scroll to zoom).

const canvas = document.getElementById('globe-canvas');
const scene = new THREE.Scene();

const camera = new THREE.PerspectiveCamera(45, 1, 0.1, 100);
camera.position.set(0, 0, 3.2);

// alpha:true (a transparent canvas, relying on the page's black CSS background to show through)
// was the likely real cause of the blank screen - everything else in the pipeline (WebGL
// context, canvas sizing) was already confirmed genuinely working, which only leaves how the
// WebView/Compose composites a canvas with an alpha channel. Explicit opaque black clear color
// instead - there's no actual transparency need here.
const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: false });
renderer.setClearColor(0x000000, 1.0);
renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));

console.log('Xenos Globe: WebGL context created =', !!renderer.getContext());

const controls = new OrbitControls(camera, renderer.domElement);
controls.enableDamping = true;
controls.dampingFactor = 0.08;
controls.rotateSpeed = 0.6;
controls.minDistance = 1.4;
controls.maxDistance = 6;
controls.enablePan = false;

const EARTH_RADIUS = 1;

// Everything rotates together as one unit regardless of which visual mode is showing, so
// switching modes mid-spin doesn't visibly jump/reset orientation.
const globeRoot = new THREE.Group();
scene.add(globeRoot);

// ---- Mode 1: real day-map/normal/specular textures (Google's/mrdoob's public three.js example
// set) - real relief (normal map) and ocean shininess (specular map), not a flat color. ----
const loader = new THREE.TextureLoader();
const dayMap = loader.load('./textures/earth_day.jpg');
const normalMap = loader.load('./textures/earth_normal.jpg');
const specularMap = loader.load('./textures/earth_specular.jpg');

const earthGeometry = new THREE.SphereGeometry(EARTH_RADIUS, 96, 96);
// THREE.SphereGeometry's own built-in UV mapping puts u=0 (the texture's leftmost pixel column)
// at this app's lng=0 (prime meridian), not lng=-180 the way a standard equirectangular Earth
// image is authored (left edge = -180, center = 0). That's a real 180-degree longitude offset
// against the dot lattice/marker's own convention below (buildDotPoints/flyToCountry - already
// verified correct against real geography), which is why real landmasses in this mode were
// showing up on the opposite side of the globe from the (correct) green marker. Rotating the
// geometry once at creation, rather than offsetting every texture (day/normal/specular) it uses.
earthGeometry.rotateY(Math.PI);
const earthMaterial = new THREE.MeshPhongMaterial({
    map: dayMap,
    normalMap: normalMap,
    normalScale: new THREE.Vector2(0.85, 0.85),
    specularMap: specularMap,
    specular: new THREE.Color(0x333333),
    shininess: 12,
});
const earth = new THREE.Mesh(earthGeometry, earthMaterial);

// Thin outer shell with a Fresnel-style rim glow - the classic three.js "atmosphere" trick, real
// shader math (not a texture). Color is a live uniform, not baked into the shader source, so the
// dotted mode's rim can be recolored on the fly (see setGlobeColor below).
function buildRimGlow(radiusScale, power, color) {
    const material = new THREE.ShaderMaterial({
        uniforms: {
            uColor: { value: color },
        },
        vertexShader: `
            varying vec3 vNormal;
            void main() {
                vNormal = normalize(normalMatrix * normal);
                gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
            }
        `,
        fragmentShader: `
            uniform vec3 uColor;
            varying vec3 vNormal;
            void main() {
                float intensity = pow(0.68 - dot(vNormal, vec3(0.0, 0.0, 1.0)), ${power.toFixed(1)});
                gl_FragColor = vec4(uColor, 1.0) * intensity;
            }
        `,
        blending: THREE.AdditiveBlending,
        side: THREE.BackSide,
        transparent: true,
    });
    return new THREE.Mesh(new THREE.SphereGeometry(EARTH_RADIUS * radiusScale, 64, 64), material);
}
const atmosphere = buildRimGlow(1.15, 3.0, new THREE.Color(0x4d99ff));

const texturedGroup = new THREE.Group();
texturedGroup.add(earth, atmosphere);
globeRoot.add(texturedGroup);

// ---- Mode 4: real satellite imagery (NASA Blue Marble / Visible Earth, December 2004 composite,
// public domain - eoimages.gsfc.nasa.gov) - a genuine satellite-derived photo of the whole Earth,
// not a live tile service (see combination.md for why: a live all-zoom-levels slippy map needs a
// paid tile-provider account and an always-online architecture, neither of which fits this fully
// offline feature). Bundled at 4096x2048, resized down from the source 5400x2700 release.
// MeshBasicMaterial (unlit) rather than earth's MeshPhongMaterial - Blue Marble already has
// real-world lighting/shading baked into the photo itself, so a second runtime light pass would
// just double it up. Separate, additive mode alongside "textured" (not a replacement) per the
// standing "don't replace something known to work" rule - see combination.md.
const satelliteMap = loader.load('./textures/earth_satellite.jpg');
const satelliteMaterial = new THREE.MeshBasicMaterial({ map: satelliteMap });
// Same 180-degree longitude fix as earthGeometry above - identical root cause (THREE's default
// SphereGeometry UV origin vs. this app's lng convention), independent of which image is mapped.
const satelliteGeometry = new THREE.SphereGeometry(EARTH_RADIUS, 96, 96);
satelliteGeometry.rotateY(Math.PI);
const satelliteEarth = new THREE.Mesh(satelliteGeometry, satelliteMaterial);
const satelliteGroup = new THREE.Group();
satelliteGroup.add(satelliteEarth, buildRimGlow(1.15, 3.0, new THREE.Color(0x4d99ff)));
globeRoot.add(satelliteGroup);

// ---- Modes 2 & 3: minimalist dot-matrix globe(s) - a lattice of small dots tracing landmass
// outlines on black, built from the specular map this file already loads above (not a new
// asset). Specular maps for Earth are bright over ocean (water reflects) and dark over land
// (matte) - the same convention every mrdoob/three.js earth demo's specular texture uses - so
// land pixels are found by thresholding for DARK, not bright. Two variants share this same
// lattice-building logic: "dotted-glow" (the first version built, kept per instruction not to
// delete it - has the crisp rim ring) and "dotted-plain" (the actual reference look - plain
// black core, white dots, no rim/glow at all). ----
function buildDotPoints(image, colorHex) {
    const sampleCanvas = document.createElement('canvas');
    sampleCanvas.width = image.naturalWidth;
    sampleCanvas.height = image.naturalHeight;
    const ctx = sampleCanvas.getContext('2d');
    ctx.drawImage(image, 0, 0);
    const pixels = ctx.getImageData(0, 0, sampleCanvas.width, sampleCanvas.height).data;

    function isLandAt(lat, lng) {
        const u = (lng + 180) / 360;
        const v = (90 - lat) / 180;
        const x = Math.min(sampleCanvas.width - 1, Math.max(0, Math.floor(u * sampleCanvas.width)));
        const y = Math.min(sampleCanvas.height - 1, Math.max(0, Math.floor(v * sampleCanvas.height)));
        const idx = (y * sampleCanvas.width + x) * 4;
        const brightness = (pixels[idx] + pixels[idx + 1] + pixels[idx + 2]) / 3;
        return brightness < 90; // dark specular = land
    }

    const STEP_DEG = 2.2;
    const dotRadius = EARTH_RADIUS * 1.004; // sits just above the (invisible) raycast proxy sphere
    const positions = [];
    for (let lat = -88; lat <= 88; lat += STEP_DEG) {
        for (let lng = -180; lng < 180; lng += STEP_DEG) {
            if (!isLandAt(lat, lng)) continue;
            const latRad = lat * (Math.PI / 180);
            const lngRad = lng * (Math.PI / 180);
            const x = -dotRadius * Math.cos(latRad) * Math.cos(lngRad);
            const y = dotRadius * Math.sin(latRad);
            const z = dotRadius * Math.cos(latRad) * Math.sin(lngRad);
            positions.push(x, y, z);
        }
    }

    const geometry = new THREE.BufferGeometry();
    geometry.setAttribute('position', new THREE.BufferAttribute(new Float32Array(positions), 3));
    const material = new THREE.PointsMaterial({
        color: colorHex,
        size: 0.016,
        sizeAttenuation: true,
        transparent: true,
        opacity: 0.9,
    });
    return { points: new THREE.Points(geometry, material), material };
}

const dottedGlowGroup = new THREE.Group();
globeRoot.add(dottedGlowGroup);
// Crisp, thin rim (higher power than the textured mode's softer glow). Kept as a real reference
// (not just added to the group) so setGlobeColor below can recolor it live.
const dotRimGlow = buildRimGlow(1.02, 5.5, new THREE.Color(0xe6f2ff));
dottedGlowGroup.add(dotRimGlow);

const dottedPlainGroup = new THREE.Group();
globeRoot.add(dottedPlainGroup);

// Set once the two buildDotPoints() calls below actually run (the image loads asynchronously) -
// referenced here so setGlobeColor can recolor the dots themselves, not just the glow rim.
let dotGlowMaterial = null;
let dotPlainMaterial = null;

// The dot lattice needs the specular image's real pixels (getImageData), which the
// TextureLoader's GPU-bound texture above can't hand back - loaded a second time as a plain
// Image specifically for CPU-side sampling. Built twice (glow variant + plain variant) from the
// same loaded image once it's ready.
const specularSampleImage = new Image();
specularSampleImage.crossOrigin = 'anonymous';
specularSampleImage.onload = () => {
    const glow = buildDotPoints(specularSampleImage, 0xe6f2ff);
    dotGlowMaterial = glow.material;
    dottedGlowGroup.add(glow.points);

    const plain = buildDotPoints(specularSampleImage, 0xffffff);
    dotPlainMaterial = plain.material;
    dottedPlainGroup.add(plain.points);
};
specularSampleImage.src = './textures/earth_specular.jpg';

// Recolors whichever dotted mode is active (dots + rim glow, when the rim is present) - exposed
// for a native "COLOR" picker (GlobeActivity.kt) offering red/white/green/blue. Reserved for a
// later use beyond just cosmetics, per the user - built now regardless since the mechanism is
// the same either way.
window.setGlobeColor = function (hex) {
    const color = new THREE.Color(hex);
    if (dotGlowMaterial) dotGlowMaterial.color.set(color);
    if (dotPlainMaterial) dotPlainMaterial.color.set(color);
    dotRimGlow.material.uniforms.uColor.value.set(color);
};

// Invisible proxy sphere - always present regardless of mode, so double-click raycasting has a
// consistent, reliable target whether the dot lattice (thousands of individual Points, a poor
// raycast target) or the solid textured mesh is what's actually showing.
const raycastProxy = new THREE.Mesh(
    new THREE.SphereGeometry(EARTH_RADIUS, 32, 32),
    new THREE.MeshBasicMaterial({ transparent: true, opacity: 0 })
);
globeRoot.add(raycastProxy);

// Selected-country marker (COUNTRY picker, GlobeActivity.kt) - a real object in globeRoot, not
// a screen-space overlay, so it naturally keeps tracking the right real-world location as the
// globe's idle auto-spin keeps running (deliberately never paused for a selection - see
// combination.md). Hidden (opacity 0) until a country is actually picked.
const countryMarker = new THREE.Mesh(
    new THREE.SphereGeometry(0.018, 16, 16),
    new THREE.MeshBasicMaterial({ color: 0x4caf50, transparent: true, opacity: 0 })
);
globeRoot.add(countryMarker);

// ---- Country borders (Globe map stage 2, agreed with Codex in combination.md) - Natural
// Earth's Admin 0 Countries, 110m resolution (public domain, naturalearthdata.com), compacted to
// name/cca2/polygon-ring coordinates only (app/src/main/assets/globe/country_borders.json).
// Built lazily on first enable (fetched + turned into line geometry only once BORDERS is first
// switched on), not at Globe open, so the geometry cost isn't paid for a layer many users may
// never turn on. Off by default, toggled via a new BORDERS control (GlobeActivity.kt). ----
const bordersGroup = new THREE.Group();
bordersGroup.visible = false;
globeRoot.add(bordersGroup);
let bordersBuilt = false;
let bordersBuilding = false;

// Same lat/lng->3D embedding used everywhere else in this file (dot lattice, marker,
// flyToCountry) - already confirmed correct against real geography, borders need to line up with
// that, not with THREE.SphereGeometry's own default UV convention (see the 180-degree fix
// earlier in this file for why those two conventions differ).
function latLngToVec3(lat, lng, radius) {
    const latRad = lat * (Math.PI / 180);
    const lngRad = lng * (Math.PI / 180);
    return new THREE.Vector3(
        -radius * Math.cos(latRad) * Math.cos(lngRad),
        radius * Math.sin(latRad),
        radius * Math.cos(latRad) * Math.sin(lngRad)
    );
}

function buildBorders() {
    if (bordersBuilt || bordersBuilding) return;
    bordersBuilding = true;
    fetch('./country_borders.json')
        .then((response) => response.json())
        .then((countries) => {
            const material = new THREE.LineBasicMaterial({ color: 0x4caf50, transparent: true, opacity: 0.65 });
            // Just above the dot lattice's own EARTH_RADIUS * 1.004 offset, so border lines don't
            // z-fight with the dots or the raycast proxy sitting right at EARTH_RADIUS.
            const radius = EARTH_RADIUS * 1.006;
            for (const country of countries) {
                for (const polygon of country.polygons) {
                    for (const ring of polygon) {
                        const points = ring.map(([lng, lat]) => latLngToVec3(lat, lng, radius));
                        const geometry = new THREE.BufferGeometry().setFromPoints(points);
                        bordersGroup.add(new THREE.LineLoop(geometry, material));
                    }
                }
            }
            bordersBuilt = true;
            bordersBuilding = false;
        })
        .catch((err) => {
            console.error('Xenos Globe: failed to load country_borders.json', err);
            bordersBuilding = false;
        });
}

window.setBordersVisible = function (visible) {
    if (visible && !bordersBuilt) buildBorders();
    bordersGroup.visible = visible;
};

// Four real modes, all built and kept (none deleted when a new one was requested):
// - "dotted-glow": the first dot-lattice version, with the crisp rim ring.
// - "dotted-plain": the actual reference look - plain black core, white dots, no rim/glow.
// - "textured": the mrdoob/three.js example Earth (day/normal/specular maps).
// - "satellite": real NASA Blue Marble satellite photo, see satelliteGroup above.
// Defaults to dotted-plain (the requested reference design). MODEL button (native Android UI,
// see GlobeActivity.kt) opens a picker calling setGlobeModel() below.
let currentMode = 'dotted-plain';
function applyMode() {
    texturedGroup.visible = currentMode === 'textured';
    dottedGlowGroup.visible = currentMode === 'dotted-glow';
    dottedPlainGroup.visible = currentMode === 'dotted-plain';
    satelliteGroup.visible = currentMode === 'satellite';
}
applyMode();
window.setGlobeModel = function (mode) {
    if (mode !== 'dotted-glow' && mode !== 'dotted-plain' && mode !== 'textured' && mode !== 'satellite') return;
    currentMode = mode;
    applyMode();
};

window.zoomGlobe = function (zoomIn) {
    const direction = camera.position.clone().normalize();
    const currentDistance = camera.position.length();
    const factor = zoomIn ? 0.82 : 1.18;
    const nextDistance = Math.min(controls.maxDistance, Math.max(controls.minDistance, currentDistance * factor));
    camera.position.copy(direction.multiplyScalar(nextDistance));
    controls.target.set(0, 0, 0);
    controls.update();
};

// Starfield backdrop - a few thousand points scattered on a large sphere, so the globe doesn't
// float in flat black.
function buildStarfield() {
    const starCount = 4000;
    const positions = new Float32Array(starCount * 3);
    for (let i = 0; i < starCount; i++) {
        const radius = 40 + Math.random() * 20;
        const theta = Math.random() * Math.PI * 2;
        const phi = Math.acos(2 * Math.random() - 1);
        positions[i * 3] = radius * Math.sin(phi) * Math.cos(theta);
        positions[i * 3 + 1] = radius * Math.sin(phi) * Math.sin(theta);
        positions[i * 3 + 2] = radius * Math.cos(phi);
    }
    const geometry = new THREE.BufferGeometry();
    geometry.setAttribute('position', new THREE.BufferAttribute(positions, 3));
    const material = new THREE.PointsMaterial({ color: 0xffffff, size: 0.06, sizeAttenuation: true });
    scene.add(new THREE.Points(geometry, material));
}
buildStarfield();

const sunLight = new THREE.DirectionalLight(0xffffff, 2.2);
sunLight.position.set(5, 2, 5);
scene.add(sunLight);
scene.add(new THREE.AmbientLight(0x404050, 1.1));

// A real, live-observed size, not window.innerWidth/innerHeight - an Android WebView embedded
// via Compose's AndroidView doesn't reliably fire a JS 'resize' event when its own container is
// laid out/resized by the host app (unlike an actual browser tab), so relying on that left the
// renderer permanently sized to whatever it read at the very first script tick - before Compose
// had necessarily finished measuring the WebView, in one observed case sizing it to 0 and
// rendering a real, non-broken scene into an invisible 0x0 buffer. ResizeObserver watches the
// canvas element's own real layout box directly, which stays accurate regardless.
function applySize(width, height) {
    if (width <= 0 || height <= 0) return;
    camera.aspect = width / height;
    camera.updateProjectionMatrix();
    renderer.setSize(width, height, false);
    console.log('Xenos Globe: sized to', width, 'x', height);
}
const resizeObserver = new ResizeObserver((entries) => {
    for (const entry of entries) {
        const box = entry.contentRect;
        applySize(box.width, box.height);
    }
});
resizeObserver.observe(canvas);
// Also apply an immediate best-effort size so the very first rendered frame isn't blank while
// waiting for ResizeObserver's own first callback (which can take a tick to fire).
applySize(canvas.clientWidth || window.innerWidth, canvas.clientHeight || window.innerHeight);

let frameCount = 0;
function animate() {
    requestAnimationFrame(animate);
    globeRoot.rotation.y += 0.0006; // slow idle spin, independent of user drag - both modes together
    controls.update();
    renderer.render(scene, camera);
    // Both prior diagnostic logs (WebGL context created / sized to) ran from synchronous setup
    // code, never from inside the actual render loop - this is the first real evidence of
    // whether requestAnimationFrame/render() are firing at all, which a hidden/not-actually-
    // visible page (Page Visibility API) could silently suspend entirely.
    if (frameCount < 3) {
        console.log('Xenos Globe: rendered frame', frameCount, 'visibilityState=', document.visibilityState, 'hidden=', document.hidden);
        frameCount++;
    }
}
animate();

// ---- Double-click/double-tap: raycast to find where on the globe was hit, convert to
// lat/lng, tell Kotlin so it can transition into the custom map view. ----

const raycaster = new THREE.Raycaster();
const pointer = new THREE.Vector2();

function cartesianToLatLng(point) {
    const normalized = point.clone().normalize();
    const lat = Math.asin(normalized.y) * (180 / Math.PI);
    const lng = Math.atan2(normalized.z, -normalized.x) * (180 / Math.PI);
    return { lat, lng };
}

function handleDoubleClick(clientX, clientY) {
    pointer.x = (clientX / canvas.clientWidth) * 2 - 1;
    pointer.y = -(clientY / canvas.clientHeight) * 2 + 1;
    raycaster.setFromCamera(pointer, camera);
    // Always the invisible proxy sphere, not whichever visual mode is currently showing - the
    // dot lattice (thousands of individual Points) is a poor/unreliable raycast target, and this
    // keeps double-click hit-testing identical in both modes.
    const hits = raycaster.intersectObject(raycastProxy, false);
    if (hits.length === 0) return;

    // Raycaster hits are in WORLD space, but globeRoot keeps spinning (idle auto-spin, never
    // paused - see combination.md), so its current rotation has to be undone before the hit point
    // means anything as a real lat/lng - otherwise the reported location silently drifts the
    // longer the app has been open, landing almost anywhere (mostly ocean, since most of Earth's
    // surface is ocean - this is why it looked like it "always shows blue"). countryMarker/
    // flyToCountry never had this problem since they set a LOCAL-space position on a globeRoot
    // child, which rotates along with it automatically; this raycast path needs the same
    // correction applied explicitly.
    const localPoint = globeRoot.worldToLocal(hits[0].point.clone());
    const { lat, lng } = cartesianToLatLng(localPoint);

    // Real visual feedback, not just a silent callback - fly the camera in toward the tapped
    // point so double-clicking genuinely feels like "entering" that location.
    flyCameraToward(hits[0].point.clone().normalize());

    if (window.XenosGlobeBridge && window.XenosGlobeBridge.onDoubleClick) {
        window.XenosGlobeBridge.onDoubleClick(lat, lng);
    }
}

// Inverse of cartesianToLatLng above (same convention buildDotPoints already uses for placing
// dots: x = -cos(lat)*cos(lng), y = sin(lat), z = cos(lat)*sin(lng) on a unit sphere) - lets the
// native COUNTRY picker (GlobeActivity.kt) fly the camera to a country by lat/lng without
// needing its own copy of the globe's coordinate math.
window.flyToCountry = function (lat, lng) {
    const latRad = lat * (Math.PI / 180);
    const lngRad = lng * (Math.PI / 180);
    const direction = new THREE.Vector3(
        -Math.cos(latRad) * Math.cos(lngRad),
        Math.sin(latRad),
        Math.cos(latRad) * Math.sin(lngRad)
    );
    flyCameraToward(direction);
    countryMarker.position.copy(direction).multiplyScalar(EARTH_RADIUS * 1.02);
    countryMarker.material.opacity = 1;
};

let flightAnimationId = null;
function flyCameraToward(direction) {
    if (flightAnimationId) cancelAnimationFrame(flightAnimationId);
    const startPos = camera.position.clone();
    const targetDistance = 1.6;
    const endPos = direction.clone().multiplyScalar(targetDistance);
    const startTime = performance.now();
    const durationMs = 900;

    function step(now) {
        const t = Math.min(1, (now - startTime) / durationMs);
        const eased = 1 - Math.pow(1 - t, 3);
        camera.position.lerpVectors(startPos, endPos, eased);
        controls.update();
        if (t < 1) {
            flightAnimationId = requestAnimationFrame(step);
        } else {
            flightAnimationId = null;
        }
    }
    flightAnimationId = requestAnimationFrame(step);
}

renderer.domElement.addEventListener('dblclick', (event) => {
    handleDoubleClick(event.clientX, event.clientY);
});

// Chromium/WebView already synthesizes a real 'dblclick' from a double-tap gesture on touch
// devices, same as desktop - no separate manual touch-timing code needed here.
