// The evolution page's live 3D viewer: one renderer in the modal's stage, each vehicle built once (vehicles.js) and
// kept, seen in the same studio light as its picture on the page; turn it, zoom toward the pointer, pan with two fingers.
// It stands on a softly lit floor in the mode's colour (--evo-floor on the stage) that its shadow falls on, so the
// rocket, its shadow and its dark engines read even on the dark modes' backgrounds; reset() brings the first view back.
import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { buildVehicle, studioEnv } from './vehicles.js';

let renderer, scene, camera, controls, env, key, rim, hemi, ground, shadowMat, floor, floorMat, holder, raf = 0, ro = null, stageEl = null, idle = 0, lastV = null, rollPending = 0;
// stage separation (Starship): the ship lifts SEP metres off the booster and back, eased, as on the 3D Starship page;
// the camera eases back at the same time so both stages stay in view
const SEP = 28;
let ship = null, sep = 0, sepT = 0, camFrom = null, camTo = null, camT = 1, lastT = 0;
const sstep = x => x * x * (3 - 2 * x);
const tallNow = () => lastV ? lastV.h + SEP * sstep(sep) : 0;
const cache = new Map();
const small = matchMedia('(max-width: 760px)').matches;

// the floor: a pool of light under the rocket fading out to the edges (white in the texture, tinted by the mode's colour)
function floorTexture() {
  const c = document.createElement('canvas'); c.width = c.height = 256;
  const g = c.getContext('2d'), gr = g.createRadialGradient(128, 128, 0, 128, 128, 128);
  gr.addColorStop(0, 'rgba(255,255,255,1)'); gr.addColorStop(0.45, 'rgba(255,255,255,0.75)'); gr.addColorStop(1, 'rgba(255,255,255,0)');
  g.fillStyle = gr; g.fillRect(0, 0, 256, 256);
  const t = new THREE.CanvasTexture(c); t.colorSpace = THREE.SRGBColorSpace; return t;
}

function init(stage) {
  stageEl = stage;
  renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
  renderer.setPixelRatio(Math.min(devicePixelRatio || 1, small ? 2 : 1.75));
  renderer.toneMapping = THREE.ACESFilmicToneMapping; renderer.toneMappingExposure = 1.0;
  renderer.outputColorSpace = THREE.SRGBColorSpace; renderer.setClearColor(0x000000, 0);
  renderer.shadowMap.enabled = true; renderer.shadowMap.type = THREE.PCFSoftShadowMap;
  stage.prepend(renderer.domElement);
  scene = new THREE.Scene(); env = studioEnv(renderer); scene.environment = env;
  camera = new THREE.PerspectiveCamera(30, 1, 0.1, 3000);
  controls = new OrbitControls(camera, renderer.domElement);
  controls.enableDamping = true; controls.dampingFactor = 0.08; controls.zoomToCursor = true; controls.screenSpacePanning = true;
  controls.maxPolarAngle = Math.PI * 0.56; controls.autoRotate = true; controls.autoRotateSpeed = 0.7;
  controls.addEventListener('start', () => { controls.autoRotate = false; clearTimeout(idle); camT = 1; });   // a touch stops the camera's own ease
  // zoom toward the pointer with a mouse only: on a touch screen a pinch zoomed toward the floor under the fingers and
  // threw the rocket out of view - a pinch zooms toward the rocket itself (two fingers still move it, to see its ends)
  renderer.domElement.addEventListener('pointerdown', e => { controls.zoomToCursor = e.pointerType === 'mouse'; }, { capture: true });
  // the mouse's sideways wheel (or a trackpad's sideways swipe) turns the rocket on its own axis, as on the 3D Starship
  // page; the up-and-down wheel still zooms
  renderer.domElement.addEventListener('wheel', e => {
    if (Math.abs(e.deltaX) <= Math.abs(e.deltaY)) return;
    e.preventDefault(); e.stopImmediatePropagation();
    const dx = e.deltaMode === 1 ? e.deltaX * 33 : e.deltaX;
    rollPending = Math.min(Math.max(rollPending + Math.min(Math.max(dx, -240), 240) * 0.0026, -3), 3);
    controls.autoRotate = false; clearTimeout(idle); idle = setTimeout(() => { controls.autoRotate = true; }, 6000);
  }, { capture: true, passive: false });
  // and whatever the gesture, the point the view turns around stays on the rocket, so it can't be lost off the screen
  controls.addEventListener('change', () => {
    if (!lastV) return;
    const t = controls.target, H = tallNow(), r = Math.max(lastV.d, 4) * 1.5;
    const y = Math.min(Math.max(t.y, 0), H), x = Math.min(Math.max(t.x, -r), r), z = Math.min(Math.max(t.z, -r), r);
    if (x !== t.x || y !== t.y || z !== t.z) { const d = new THREE.Vector3(x - t.x, y - t.y, z - t.z); t.add(d); camera.position.add(d); }
  });
  controls.addEventListener('end', () => { clearTimeout(idle); idle = setTimeout(() => { controls.autoRotate = true; }, 6000); });
  key = new THREE.DirectionalLight(0xfff1e0, 2.9); key.castShadow = true; key.shadow.mapSize.set(2048, 2048); key.shadow.bias = -0.0004; key.shadow.normalBias = 0.02;
  hemi = new THREE.HemisphereLight(0xcfdcff, 0x3a3630, 0.55);
  scene.add(key, key.target, hemi);
  rim = new THREE.DirectionalLight(0xbfd6ff, 1.5); scene.add(rim);
  // the floor's pool of light, and the shadow on it
  floorMat = new THREE.MeshBasicMaterial({ map: floorTexture(), transparent: true, depthWrite: false, color: 0x3a4150, toneMapped: false });
  floor = new THREE.Mesh(new THREE.CircleGeometry(1, 64), floorMat); floor.rotation.x = -Math.PI / 2; floor.renderOrder = -2; scene.add(floor);
  shadowMat = new THREE.ShadowMaterial({ opacity: 0.4 });
  ground = new THREE.Mesh(new THREE.CircleGeometry(1, 64), shadowMat); ground.rotation.x = -Math.PI / 2; ground.position.y = 0.01; ground.receiveShadow = true; ground.renderOrder = -1; scene.add(ground);
  holder = new THREE.Group(); scene.add(holder);
  ro = new ResizeObserver(resize); ro.observe(stage); resize();
}
function resize() {
  const w = stageEl.clientWidth, h = stageEl.clientHeight; if (!w || !h) return;
  renderer.setSize(w, h, false); camera.aspect = w / h; camera.updateProjectionMatrix();
}
function loop(now = performance.now()) {
  raf = requestAnimationFrame(loop);
  const dt = Math.min(0.25, Math.max(0, (now - (lastT || now)) / 1000)); lastT = now;   // seconds since the last frame
  if (Math.abs(rollPending) > 1e-4) { const step = rollPending * 0.18; holder.rotation.y += step; rollPending -= step; }   // the sideways wheel's turn, eased
  if (ship && sep !== sepT) {   // the separation, 1.4 s each way whatever the frame rate
    sep = sepT > sep ? Math.min(sepT, sep + dt / 1.4) : Math.max(sepT, sep - dt / 1.4);
    ship.position.y = ship.userData.baseY + SEP * sstep(sep);
  }
  if (camT < 1) {   // the camera's own ease to the new framing, kept in step with the ship
    camT = Math.min(1, camT + dt / 1.4); const e = sstep(camT);
    controls.target.lerpVectors(camFrom.t, camTo.t, e); camera.position.lerpVectors(camFrom.p, camTo.p, e);
  }
  controls.update(); renderer.render(scene, camera);
}
export function pause() { cancelAnimationFrame(raf); raf = 0; lastT = 0; }

// the floor and the light in the mode's colours (read each time a rocket is shown - the mode may have changed)
function applyMode() {
  const cs = getComputedStyle(stageEl);
  const f = (cs.getPropertyValue('--evo-floor') || '').trim() || '#3a4150';
  try { floorMat.color.set(f); } catch (e) { floorMat.color.set('#3a4150'); }
  const light = /^(1|true)$/.test((cs.getPropertyValue('--evo-light') || '').trim());
  shadowMat.opacity = light ? 0.3 : 0.45;
  hemi.intensity = light ? 0.4 : 0.6;
}

// the first view of a rocket: all of it in view from its picture's side, a little from below its middle
function frame(v, H = v.h) {
  const W = Math.max(v.d, 4), az = (v.az != null ? v.az : -24) * Math.PI / 180;
  const fov = camera.fov * Math.PI / 180, aspect = camera.aspect || 1;
  const distH = (H * 0.56) / Math.tan(fov / 2), distW = (W * 1.4) / (Math.tan(fov / 2) * aspect);
  const dist = Math.max(distH, distW, 12);
  controls.target.set(0, H * 0.48, 0);
  camera.position.set(Math.sin(az) * dist, H * 0.5 + dist * 0.06, Math.cos(az) * dist);
  camera.near = Math.max(0.05, dist / 400); camera.far = dist * 20; camera.updateProjectionMatrix();
  controls.minDistance = Math.max(3, W * 0.9); controls.maxDistance = dist * 2.2;
  holder.rotation.set(0, 0, 0); rollPending = 0;
  controls.autoRotate = true; clearTimeout(idle); controls.update();
  return { H, W, az };
}

export async function show(stage, v, stillCurrent) {
  if (!renderer) init(stage);
  if (!raf) loop();
  let model = cache.get(v.id);
  if (!model) { model = await buildVehicle(v.id, renderer, env); cache.set(v.id, model); }
  if (!stillCurrent()) return;
  holder.clear(); holder.add(model); lastV = v;
  // a Starship stands stacked when shown (its ship back on the booster, if it was left apart)
  ship = model.userData.ship || null; sep = sepT = 0; camT = 1;
  if (ship) { if (ship.userData.baseY == null) ship.userData.baseY = ship.position.y; ship.position.y = ship.userData.baseY; }
  applyMode();
  const { H, W, az } = frame(v);
  // the light follows the rocket's size
  const L = Math.max(H, 20);
  key.position.set(Math.sin(az + 1.15) * L * 1.2, L * 0.95, Math.cos(az + 1.15) * L * 1.2); key.target.position.set(0, H * 0.4, 0);
  const sc = key.shadow.camera; sc.left = -H * 2.2; sc.right = H * 2.2; sc.top = H * 2.2; sc.bottom = -H * 2.2; sc.near = 1; sc.far = L * 4; sc.updateProjectionMatrix();
  rim.position.set(Math.sin(az - 2.2) * L, L * 0.4, Math.cos(az - 2.2) * L);
  const r = Math.max(W * 3, H * 1.6, 14);
  ground.scale.setScalar(Math.max(W * 3, H * 2.4, 14));
  floor.scale.setScalar(r);
}

// back to the first view of the rocket on show (taller by the gap while its stages are apart)
export function reset() { if (lastV && camera) { camT = 1; frame(lastV, lastV.h + SEP * sepT); } }

// separate the stages (on: the ship lifts off the booster) or stack them again; the view keeps the side it's seen from
// and eases back (or in again) so the whole vehicle stays on the screen
export function separate(on) {
  if (!ship || !lastV) return false;
  sepT = on ? 1 : 0;
  const H = lastV.h + SEP * sepT, W = Math.max(lastV.d, 4);
  const fov = camera.fov * Math.PI / 180, aspect = camera.aspect || 1;
  const need = Math.max((H * 0.56) / Math.tan(fov / 2), (W * 1.4) / (Math.tan(fov / 2) * aspect), 12);
  const dir = camera.position.clone().sub(controls.target), cur = dir.length();
  const dist = on ? Math.max(cur, need) : Math.min(cur, need);
  const t = new THREE.Vector3(0, H * 0.48, 0);
  camFrom = { t: controls.target.clone(), p: camera.position.clone() };
  camTo = { t, p: t.clone().add(dir.normalize().multiplyScalar(dist)) };
  controls.maxDistance = Math.max(controls.maxDistance, dist * 2.2); camera.far = Math.max(camera.far, dist * 20); camera.updateProjectionMatrix();
  camT = 0;
  return true;
}
export const hasStages = () => !!ship;
