// The evolution page's live 3D viewer: one renderer in the modal's stage, each vehicle built once (vehicles.js) and
// kept, seen in the same studio light as its picture on the page; turn it, zoom toward the pointer, pan with two fingers.
import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { buildVehicle, studioEnv } from './vehicles.js';

let renderer, scene, camera, controls, env, key, rim, ground, shadowMat, holder, raf = 0, ro = null, stageEl = null, idle = 0;
const cache = new Map();
const small = matchMedia('(max-width: 760px)').matches;

function init(stage) {
  stageEl = stage;
  renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
  renderer.setPixelRatio(Math.min(devicePixelRatio || 1, small ? 2 : 1.75));
  renderer.toneMapping = THREE.ACESFilmicToneMapping; renderer.toneMappingExposure = 0.95;
  renderer.outputColorSpace = THREE.SRGBColorSpace; renderer.setClearColor(0x000000, 0);
  renderer.shadowMap.enabled = true; renderer.shadowMap.type = THREE.PCFSoftShadowMap;
  stage.prepend(renderer.domElement);
  scene = new THREE.Scene(); env = studioEnv(renderer); scene.environment = env;
  camera = new THREE.PerspectiveCamera(30, 1, 0.1, 3000);
  controls = new OrbitControls(camera, renderer.domElement);
  controls.enableDamping = true; controls.dampingFactor = 0.08; controls.zoomToCursor = true; controls.screenSpacePanning = true;
  controls.maxPolarAngle = Math.PI * 0.56; controls.autoRotate = true; controls.autoRotateSpeed = 0.7;
  controls.addEventListener('start', () => { controls.autoRotate = false; clearTimeout(idle); });
  controls.addEventListener('end', () => { clearTimeout(idle); idle = setTimeout(() => { controls.autoRotate = true; }, 6000); });
  key = new THREE.DirectionalLight(0xfff1e0, 2.9); key.castShadow = true; key.shadow.mapSize.set(2048, 2048); key.shadow.bias = -0.0004; key.shadow.normalBias = 0.02;
  scene.add(key, key.target, new THREE.HemisphereLight(0xcfdcff, 0x1c1a18, 0.32));
  rim = new THREE.DirectionalLight(0xbfd6ff, 1.3); scene.add(rim);
  // a soft shadow on an invisible floor, so the rocket stands on something
  shadowMat = new THREE.ShadowMaterial({ opacity: 0.22 });
  ground = new THREE.Mesh(new THREE.CircleGeometry(1, 64), shadowMat); ground.rotation.x = -Math.PI / 2; ground.receiveShadow = true; scene.add(ground);
  holder = new THREE.Group(); scene.add(holder);
  ro = new ResizeObserver(resize); ro.observe(stage); resize();
}
function resize() {
  const w = stageEl.clientWidth, h = stageEl.clientHeight; if (!w || !h) return;
  renderer.setSize(w, h, false); camera.aspect = w / h; camera.updateProjectionMatrix();
}
function loop() { raf = requestAnimationFrame(loop); controls.update(); renderer.render(scene, camera); }
export function pause() { cancelAnimationFrame(raf); raf = 0; }

export async function show(stage, v, stillCurrent) {
  if (!renderer) init(stage);
  if (!raf) loop();
  let model = cache.get(v.id);
  if (!model) { model = await buildVehicle(v.id, renderer, env); cache.set(v.id, model); }
  if (!stillCurrent()) return;
  holder.clear(); holder.add(model);
  // frame it: the whole rocket in view from its picture's side, a little from below its middle
  const H = v.h, W = Math.max(v.d, 4), az = (v.az != null ? v.az : -24) * Math.PI / 180;
  const fov = camera.fov * Math.PI / 180, aspect = camera.aspect || 1;
  const distH = (H * 0.56) / Math.tan(fov / 2), distW = (W * 1.4) / (Math.tan(fov / 2) * aspect);
  const dist = Math.max(distH, distW, 12);
  controls.target.set(0, H * 0.48, 0);
  camera.position.set(Math.sin(az) * dist, H * 0.5 + dist * 0.06, Math.cos(az) * dist);
  camera.near = Math.max(0.05, dist / 400); camera.far = dist * 20; camera.updateProjectionMatrix();
  controls.minDistance = Math.max(3, W * 0.9); controls.maxDistance = dist * 2.2;
  controls.autoRotate = true; controls.update();
  // the light follows the rocket's size
  const L = Math.max(H, 20);
  key.position.set(Math.sin(az + 1.15) * L * 1.2, L * 0.95, Math.cos(az + 1.15) * L * 1.2); key.target.position.set(0, H * 0.4, 0);
  const sc = key.shadow.camera, s = Math.max(W * 2.5, 12); sc.left = -H * 2.2; sc.right = H * 2.2; sc.top = H * 2.2; sc.bottom = -H * 2.2; sc.near = 1; sc.far = L * 4; sc.updateProjectionMatrix();
  rim.position.set(Math.sin(az - 2.2) * L, L * 0.4, Math.cos(az - 2.2) * L);
  ground.scale.setScalar(Math.max(W * 3, H * 2.4, 14));
}
