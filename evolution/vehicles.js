// The vehicles of the evolution page (evolution/index.html), as 3D models in metres: y up, the base at y = 0, the side
// that faces the viewer at +z. The Falcon family and Starhopper are built here; Starship V3 is the 3D Starship's own
// model (starship-3d.html, between its @model markers - the same code the launch sim runs), V1 and V2 the same model
// changed where those versions differ (four smaller grid fins, the separate vented hot-stage ring, a shorter ship).
import * as THREE from 'three';
import { mergeGeometries } from 'three/addons/utils/BufferGeometryUtils.js';

import { VEHICLES } from './data.js';
export { VEHICLES };

const TAU = Math.PI * 2;
const lathe = (pts, seg = 64) => new THREE.LatheGeometry(pts.map(([r, y]) => new THREE.Vector2(Math.max(0.0005, r), y)), seg);
const cylY = (r, y0, y1, seg = 64, open = false) => { const g = new THREE.CylinderGeometry(r, r, y1 - y0, seg, 1, open); g.translate(0, (y0 + y1) / 2, 0); return g; };
const cone = (r0, r1, y0, y1, seg = 64, open = false) => { const g = new THREE.CylinderGeometry(r1, r0, y1 - y0, seg, 1, open); g.translate(0, (y0 + y1) / 2, 0); return g; };
const boxAt = (w, h, d, x, y, z) => { const g = new THREE.BoxGeometry(w, h, d); g.translate(x, y, z); return g; };
const mesh = (g, m) => { const o = new THREE.Mesh(g, m); o.castShadow = o.receiveShadow = true; return o; };
// an ogive nose (tangent ogive) from radius r at y0 to a rounded point at y0 + L
function ogive(r, y0, L, tip = 0.04, n = 28) {
  const rho = (r * r + L * L) / (2 * r), pts = [];
  for (let i = 0; i <= n; i++) { const y = L * i / n, x = Math.sqrt(Math.max(0, rho * rho - y * y)) + r - rho; pts.push([Math.max(tip * r * (i === n ? 0 : 1), x), y0 + y]); }
  pts[pts.length - 1][0] = 0.0005;
  return pts;
}

// ---------------------------------------------------------------- materials and textures
function canvasTex(w, h, draw, srgb = true) {
  const c = document.createElement('canvas'); c.width = w; c.height = h; draw(c.getContext('2d'), w, h);
  const t = new THREE.CanvasTexture(c); if (srgb) t.colorSpace = THREE.SRGBColorSpace; t.anisotropy = 8; return t;
}
// a painted stage: white with faint panel lines and a little grime low down; optional vertical "SPACEX" and a flag
function stageTex(opts = {}) {
  const { label = null, labelColor = '#14161a', soot = 0, flag = false, h = 4096, labelPos = 0.55, labelSize = 0.11, rings = 6,
    stack = false, stackColor = '#1f3d8a', stackSize = 0.045, logo = null, flagPos = 0.06, logoSize = 0.011, flagScale = 1 } = opts;
  return canvasTex(1024, h, (g, W, H) => {
    g.fillStyle = '#f4f5f6'; g.fillRect(0, 0, W, H);
    // the very slight unevenness of paint over long panels
    for (let i = 0; i < 26; i++) { const y = Math.random() * H; g.fillStyle = `rgba(${Math.random() < 0.5 ? '0,0,0' : '255,255,255'},${0.012 + Math.random() * 0.02})`; g.fillRect(0, y, W, 6 + Math.random() * 80); }
    // weld seams round the tank
    for (let i = 1; i < rings; i++) { g.fillStyle = 'rgba(70,76,86,0.20)'; g.fillRect(0, H * i / rings, W, 4); g.fillStyle = 'rgba(255,255,255,0.35)'; g.fillRect(0, H * i / rings + 4, W, 2); }
    // long streaks of exhaust and rain down the paint
    for (let i = 0; i < 70; i++) { const x = Math.random() * W, y = Math.random() * H * 0.7, l = 200 + Math.random() * 900, gr = g.createLinearGradient(0, y, 0, y + l); gr.addColorStop(0, 'rgba(60,56,50,0)'); gr.addColorStop(0.5, `rgba(60,56,50,${0.02 + soot * 0.6 * Math.random()})`); gr.addColorStop(1, 'rgba(60,56,50,0)'); g.fillStyle = gr; g.fillRect(x, y, 2 + Math.random() * 6, l); }
    if (soot > 0) { const heavy = soot >= 0.3, gr = g.createLinearGradient(0, H, 0, H * (1 - soot)); gr.addColorStop(0, `rgba(34,31,28,${heavy ? 0.92 : 0.55})`); if (heavy) gr.addColorStop(0.55, 'rgba(34,31,28,0.8)'); gr.addColorStop(1, 'rgba(40,36,32,0)'); g.fillStyle = gr; g.fillRect(0, H * (1 - soot), W, H * soot); }
    if (soot >= 0.15) {   // grey streaks of soot all the way up, as on a booster that has flown many times
      for (let i = 0; i < soot * 900; i++) { const x = Math.random() * W, y = Math.random() * H * 0.9, l = 150 + Math.random() * 1400, a = 0.05 + Math.random() * soot * 0.7, gr = g.createLinearGradient(0, y, 0, y + l); gr.addColorStop(0, 'rgba(70,66,62,0)'); gr.addColorStop(0.4, `rgba(70,66,62,${a})`); gr.addColorStop(1, `rgba(70,66,62,${a * 0.4})`); g.fillStyle = gr; g.fillRect(x, y, 3 + Math.random() * 24, l); }
    }
    const cx = W * 0.94;   // the side facing the viewer
    if (label && stack) {   // the 2010-2017 livery: blue letters one under another, the X with its swoosh
      const px = Math.round(H * stackSize), gap = px * 1.08, n = label.length, y0 = H * labelPos - gap * (n - 1) / 2;
      g.font = `700 ${px}px 'Helvetica Neue', Arial, sans-serif`; g.textAlign = 'center'; g.textBaseline = 'middle'; g.fillStyle = stackColor;
      for (let k = 0; k < n; k++) { g.save(); g.translate(cx, y0 + k * gap); g.scale(0.62, 1); g.fillText(label[k], 0, 0); g.restore(); }
      const yx = y0 + (n - 1) * gap; g.strokeStyle = stackColor; g.lineWidth = px * 0.07; g.lineCap = 'round';
      g.beginPath(); g.moveTo(cx - px * 0.5, yx + px * 0.42); g.quadraticCurveTo(cx - px * 0.06, yx - px * 0.05, cx + px * 0.45, yx - px * 0.5); g.stroke();
    } else if (label) {   // the Block 5 livery: one black word read bottom-to-top
      for (const x of [cx, cx - W]) {
        g.save(); g.translate(x, H * labelPos); g.rotate(-Math.PI / 2);
        const px = Math.round(H * labelSize * 0.32); g.font = `700 ${px}px 'Arial Black', 'Helvetica Neue', Arial, sans-serif`;
        g.textAlign = 'center'; g.textBaseline = 'middle'; g.fillStyle = labelColor;
        g.save(); g.scale(1, 1.6); g.fillText(label, 0, 0); g.restore(); g.restore();
      }
    }
    if (flag) {   // near the top of the stage: the vehicle's name, and the US flag under it (stripes across)
      const fy = H * flagPos;
      if (logo) { g.font = `700 ${Math.round(H * logoSize)}px 'Helvetica Neue', Arial, sans-serif`; g.textAlign = 'center'; g.textBaseline = 'middle'; g.fillStyle = '#2a2e36'; for (const x of [cx, cx - W]) { g.save(); g.translate(x, fy); g.scale(0.62, 1); g.fillText(logo, 0, 0); if (logo === 'FH') { const u = H * logoSize; g.strokeStyle = '#c8202f'; g.lineWidth = u * 0.16; g.lineCap = 'round'; g.beginPath(); g.moveTo(-u * 1.1, -u * 0.55); g.quadraticCurveTo(0, -u * 1.25, u * 1.2, -u * 0.95); g.stroke(); } g.restore(); } }
      const fw = 104 * flagScale, fh = 64 * flagScale, fx = cx - fw / 2, fy2 = fy + H * logoSize * 1.1;
      for (let k = 0; k < 13; k++) { g.fillStyle = k % 2 ? '#ffffff' : '#b22234'; g.fillRect(fx, fy2 + k * fh / 13, fw, fh / 13 + 0.5); }
      g.fillStyle = '#3c3b6e'; g.fillRect(fx, fy2, fw * 0.42, fh * 7 / 13);
    }
  });
}
function carbonTex() {
  return canvasTex(512, 512, (g, W, H) => {
    g.fillStyle = '#16181b'; g.fillRect(0, 0, W, H);
    for (let y = 0; y < H; y += 8) for (let x = 0; x < W; x += 8) { const v = ((x + y) / 8) % 2 ? 26 : 20; g.fillStyle = `rgb(${v},${v + 1},${v + 3})`; g.fillRect(x, y, 8, 8); }
  });
}
function steelTex(seed = 3) {   // Starhopper's welded stainless: uneven sheets, dents and heat tint
  let s = seed; const rnd = () => (s = (s * 16807) % 2147483647) / 2147483647;
  return canvasTex(2048, 1024, (g, W, H) => {
    const rows = 9;
    for (let r = 0; r < rows; r++) {
      const y0 = r * H / rows, sheets = 5, off = rnd() * W;
      for (let k = 0; k < sheets; k++) { const v = 145 + rnd() * 70; g.fillStyle = `rgb(${v | 0},${v | 0},${(v + 6) | 0})`; const x0 = (off + k * W / sheets) % W; g.fillRect(x0, y0, W / sheets + 2, H / rows + 1); if (x0 + W / sheets > W) g.fillRect(x0 - W, y0, W / sheets + 2, H / rows + 1); }
    }
    for (let k = 0; k < 6000; k++) { g.fillStyle = `rgba(${rnd() < 0.5 ? '255,255,255' : '30,30,34'},${0.02 + rnd() * 0.04})`; g.fillRect(rnd() * W, rnd() * H, 30 + rnd() * 260, 1); }
    for (let k = 0; k < 40; k++) { const x = rnd() * W, y = rnd() * H, r = 30 + rnd() * 140, gr = g.createRadialGradient(x, y, 0, x, y, r); gr.addColorStop(0, rnd() < 0.5 ? 'rgba(150,110,70,0.18)' : 'rgba(40,40,50,0.16)'); gr.addColorStop(1, 'rgba(0,0,0,0)'); g.fillStyle = gr; g.fillRect(x - r, y - r, 2 * r, 2 * r); }
    // wrinkles: soft vertical light and dark bands where the thin sheets buckled between the welds
    for (let k = 0; k < 220; k++) { const x = rnd() * W, w = 6 + rnd() * 40, y = Math.floor(rnd() * 9) * H / 9, gr = g.createLinearGradient(x, 0, x + w, 0); const c = rnd() < 0.5 ? '255,255,255' : '20,20,24'; gr.addColorStop(0, `rgba(${c},0)`); gr.addColorStop(0.5, `rgba(${c},${0.08 + rnd() * 0.12})`); gr.addColorStop(1, `rgba(${c},0)`); g.fillStyle = gr; g.fillRect(x, y, w, H / 9); }
    // heat and grime low down, from the Raptor's runs
    const lo = g.createLinearGradient(0, H, 0, H * 0.55); lo.addColorStop(0, 'rgba(70,52,36,0.55)'); lo.addColorStop(1, 'rgba(70,52,36,0)'); g.fillStyle = lo; g.fillRect(0, H * 0.55, W, H * 0.45);
    for (let r = 0; r <= rows; r++) { g.fillStyle = 'rgba(60,50,40,0.75)'; g.fillRect(0, r * H / rows - 3, W, 6); g.fillStyle = 'rgba(255,240,220,0.25)'; g.fillRect(0, r * H / rows + 3, W, 2); }
    // the vertical welds between sheets
    for (let r = 0; r < rows; r++) for (let k = 0; k < 5; k++) { g.fillStyle = 'rgba(60,50,40,0.55)'; g.fillRect((rnd() * W) | 0, r * H / rows, 4, H / rows); }
  });
}

export function materials(envMap) {
  const std = (o) => new THREE.MeshStandardMaterial(Object.assign({ envMap, envMapIntensity: 0.6 }, o));
  return {
    white: std({ color: 0xeceef0, roughness: 0.5, metalness: 0.02 }),
    black: std({ color: 0xffffff, map: carbonTex(), roughness: 0.5, metalness: 0.15 }),
    blackPlain: std({ color: 0x17191c, roughness: 0.55, metalness: 0.2 }),
    engine: std({ color: 0x2c2f34, roughness: 0.35, metalness: 0.8 }),
    engineIn: std({ color: 0x0b0c0e, roughness: 0.6, metalness: 0.4, side: THREE.BackSide }),
    nozzleCopper: std({ color: 0x6d4a35, roughness: 0.38, metalness: 0.85 }),
    metal: std({ color: 0x8c9198, roughness: 0.38, metalness: 0.85 }),
    titanium: std({ color: 0x4f535a, roughness: 0.55, metalness: 0.8 }),
    steel: std({ color: 0xffffff, map: steelTex(), roughness: 0.4, metalness: 0.9, envMapIntensity: 1.0 }),
    dragonWhite: std({ color: 0xeef0f2, roughness: 0.5, metalness: 0.05 }),
    heatShield: std({ color: 0x3a3430, roughness: 0.85, metalness: 0.05 }),
    window: std({ color: 0x0f1216, roughness: 0.15, metalness: 0.6 }),
    solar: std({ color: 0x1a2440, roughness: 0.3, metalness: 0.6 }),
  };
}

// ---------------------------------------------------------------- parts
// a Merlin engine: turbopump block and the bell, open at the bottom
function merlin(M, x, z, y, bellH = 1.15, bellR = 0.46, vac = false) {
  const g = new THREE.Group();
  const prof = []; for (let i = 0; i <= 16; i++) { const t = i / 16; prof.push([0.14 + (bellR - 0.14) * Math.pow(t, 0.62), -t * bellH]); }
  const bell = new THREE.LatheGeometry(prof.map(([r, yy]) => new THREE.Vector2(r, yy)), 40);
  g.add(mesh(bell, vac ? M.nozzleCopper : M.engine));
  const inner = bell.clone(); inner.scale(0.97, 1, 0.97); g.add(mesh(inner, M.engineIn));
  g.add(mesh(cylY(0.16, 0, 0.35, 20), M.metal));
  g.add(mesh(boxAt(0.22, 0.3, 0.18, 0.18, 0.2, 0), M.metal));
  g.position.set(x, y, z); return g;
}
// a landing leg folded up the side of a Falcon first stage (black carbon, the foot at the bottom)
function foldedLeg(M, a, R, y0, len) {
  const g = new THREE.Group();
  const body = new THREE.BoxGeometry(0.55, len, 0.22); body.translate(0, len / 2, 0); g.add(mesh(body, M.black));
  const foot = new THREE.BoxGeometry(0.8, 0.35, 0.3); foot.translate(0, 0.15, 0.02); g.add(mesh(foot, M.blackPlain));
  g.position.set(Math.sin(a) * (R + 0.08), y0, Math.cos(a) * (R + 0.08)); g.rotation.y = a; return g;
}
// a grid fin folded flat against the interstage (a lattice of squares in a frame)
function gridFin(M, a, R, y, w = 1.2, h = 1.45, deployed = false) {
  const g = new THREE.Group(), t = 0.06, cells = 6, parts = [];
  parts.push(boxAt(w, t, 0.12, 0, h / 2, 0), boxAt(w, t, 0.12, 0, -h / 2, 0), boxAt(t, h, 0.12, w / 2, 0, 0), boxAt(t, h, 0.12, -w / 2, 0, 0));
  for (let i = 1; i < cells; i++) {
    const p = -w / 2 + w * i / cells, q = -h / 2 + h * i / cells;
    const d1 = new THREE.BoxGeometry(0.025, h * 1.38, 0.1); d1.rotateZ(0.66); d1.translate(p - w / 2 + w * 0.0, 0, 0);
    parts.push(boxAt(0.025, h, 0.1, p, 0, 0), boxAt(w, 0.025, 0.1, 0, q, 0));
  }
  const geo = mergeGeometries(parts); const m = mesh(geo, M.titanium); g.add(m);
  if (deployed) { m.rotation.x = -Math.PI / 2; m.position.z = h / 2; }
  g.position.set(Math.sin(a) * (R + 0.12), y, Math.cos(a) * (R + 0.12)); g.rotation.y = a; return g;
}
// the payload fairing: a cylinder, then an ogive to its rounded tip; the seam between the halves
function fairing(M, rB, rF, y0, cylH, noseH, boatH = 1.0) {
  const pts = [[rB, y0], [rF, y0 + boatH], [rF, y0 + boatH + cylH]];
  ogive(rF, y0 + boatH + cylH, noseH).slice(1).forEach(p => pts.push(p));
  const g = new THREE.Group(); g.add(mesh(lathe(pts, 72), M.white));
  const seam = new THREE.BoxGeometry(0.03, cylH + boatH, 0.04); seam.translate(rF + 0.005, y0 + (boatH + cylH) / 2, 0); seam.rotateY(Math.PI / 2 - 0.25);
  g.add(mesh(seam, M.blackPlain)); return g;
}
// a Falcon 9 first-stage body (white, grime toward the bottom), the engine section at its foot
function f9Stage(M, top, opts = {}) {
  const { R = 1.83, label = 'SPACEX', legs = false, octaweb = true, soot = 0.0, flag = true, nineGrid = false, stack = false, logo = 'FALCON 9', raceDark = false, labelPos = 0.42, stackPos = 0.76 } = opts;
  const g = new THREE.Group();
  const body = cylY(R, 0.9, top, 72, true); body.rotateY(0);
  const tex = stageTex({ label, soot, flag, logo, stack, labelPos: stack ? stackPos : labelPos, labelSize: 0.13, rings: 7 });
  const mat = M.white.clone(); mat.map = tex; g.add(mesh(body, mat));
  // the engine section's skirt and its heat shield
  g.add(mesh(cone(R * 1.0, R, 0.25, 0.95, 72, true), M.blackPlain));
  g.add(mesh(cylY(R * 0.98, 0.2, 0.26, 72), M.blackPlain));
  // nine engines: octaweb (eight round one) or the v1.0 three-by-three grid
  const pos = [];
  if (nineGrid) { for (let i = -1; i <= 1; i++) for (let j = -1; j <= 1; j++) pos.push([i * 1.05, j * 1.05]); }
  else { pos.push([0, 0]); for (let i = 0; i < 8; i++) { const a = i / 8 * TAU; pos.push([Math.sin(a) * 1.25, Math.cos(a) * 1.25]); } }
  pos.forEach(([x, z]) => g.add(merlin(M, x, z, 0.25, 1.15, 0.43)));
  if (legs) for (let i = 0; i < 4; i++) g.add(foldedLeg(M, Math.PI / 4 + i * TAU / 4, R, 0.2, 9.5));
  g.add(raceway(raceDark ? M.blackPlain : M.white, R, 1.2, top, 0.3));
  return g;
}

// the raceway: the covered tube carrying cables and pressurant lines down the side of a stage
function raceway(mat, R, y0, y1, a) {
  const sh = new THREE.Shape(); sh.moveTo(-0.16, 0); sh.lineTo(-0.13, 0.1); sh.quadraticCurveTo(0, 0.17, 0.13, 0.1); sh.lineTo(0.16, 0); sh.lineTo(-0.16, 0);
  const geo = new THREE.ExtrudeGeometry(sh, { depth: y1 - y0, bevelEnabled: false }); geo.rotateX(Math.PI / 2); geo.translate(0, y1, R - 0.01);
  geo.rotateY(a); return mesh(geo, mat);
}
// ---------------------------------------------------------------- the vehicles
function falcon1(M) {
  const g = new THREE.Group(), R = 0.84;
  const t1 = M.white.clone(); t1.map = stageTex({ label: null, rings: 5, flag: true, flagPos: 0.08 });
  g.add(mesh(cylY(R, 1.0, 15.6, 48, true), t1));
  g.add(mesh(cone(R * 0.86, R, 0.55, 1.0, 48, true), M.blackPlain));
  g.add(merlin(M, 0, 0, 0.6, 1.2, 0.5));
  g.add(mesh(cylY(R, 15.6, 16.9, 48), M.blackPlain));                     // interstage
  g.add(mesh(cylY(R, 16.9, 18.3, 48), M.white));                          // second stage
  g.add(fairing(M, R, 0.77, 18.3, 1.0, 2.0, 0.05));
  return g;
}
// the first Dragon (cargo): the trunk with its solar-array covers, the capsule and its nose cap
function dragon1(M, y0) {
  const g = new THREE.Group(), R = 1.83;
  g.add(mesh(cylY(R, y0, y0 + 2.8, 64), M.dragonWhite));
  for (const s of [-1, 1]) g.add(mesh(boxAt(0.25, 2.3, 1.6, s * (R + 0.08), y0 + 1.4, 0), M.dragonWhite));   // the solar-array fairings
  g.add(mesh(cylY(R + 0.02, y0 + 2.8, y0 + 3.0, 64), M.heatShield));
  g.add(mesh(cone(R, 1.0, y0 + 3.0, y0 + 6.1, 64), M.dragonWhite));         // the capsule
  for (let i = 0; i < 2; i++) { const w = new THREE.CircleGeometry(0.17, 20); w.translate(0, 0, 0); const m = mesh(w, M.window); const a = -0.35 + i * 0.7; m.position.set(Math.sin(a) * 1.42, y0 + 4.3, Math.cos(a) * 1.42); m.rotation.y = a; m.rotation.x = -0.39; g.add(m); }
  g.add(mesh(lathe(ogive(1.0, y0 + 6.1, 1.6), 48), M.dragonWhite));         // the nose cap
  return g;
}
function f9v10(M) {
  const g = new THREE.Group(), R = 1.83;
  g.add(f9Stage(M, 29.3, { nineGrid: true, legs: false, stack: true, stackPos: 0.7 }));
  g.add(mesh(cylY(R, 29.3, 33.0, 72), M.white));                           // interstage (white on v1.0)
  g.add(mesh(cylY(R, 29.25, 29.35, 72), M.blackPlain));
  g.add(mesh(cylY(R, 33.0, 39.9, 72), M.white));                           // second stage
  g.add(dragon1(M, 39.9));
  return g;
}
// Grasshopper (2012-13, after its photos): a white Falcon 9 tank with the blue lettering and a rounded cap, standing on
// a blue steel frame that carries the single Merlin and black pressure tanks, with four long aluminium legs out to the
// ground, each braced back to the frame's foot
function grasshopper(M) {
  const g = new THREE.Group(), R = 1.83, base = 5.0, top = 30.4;
  const mat = M.white.clone(); mat.map = stageTex({ label: 'SPACEX', stack: true, labelPos: 0.78, stackSize: 0.052, rings: 6 });
  g.add(mesh(cylY(R, base, top, 72, true), mat));
  g.add(mesh(lathe([[R, top], [R * 0.97, top + 0.6], [R * 0.82, top + 1.25], [R * 0.52, top + 1.7], [0.3, top + 1.88], [0.0005, top + 1.92]], 64), M.white));
  g.add(mesh(cylY(R + 0.02, base, base + 0.25, 72), M.blackPlain));
  const blue = new THREE.MeshStandardMaterial({ color: 0x1f4ea3, roughness: 0.5, metalness: 0.3, envMap: M.white.envMap, envMapIntensity: 0.6 });
  const alu = new THREE.MeshStandardMaterial({ color: 0xc9ccd1, roughness: 0.35, metalness: 0.9, envMap: M.white.envMap, envMapIntensity: 1.1 });
  const tube = (p, q, r, m) => { const d = q.clone().sub(p), L = d.length(), c = new THREE.CylinderGeometry(r, r, L, 14); c.translate(0, L / 2, 0); const o = mesh(c, m); o.position.copy(p); o.quaternion.setFromUnitVectors(new THREE.Vector3(0, 1, 0), d.normalize()); return o; };
  // the blue frame: a square ring top and bottom, posts and crossed braces between them
  const F = 2.25, y0 = 2.7, y1 = base;
  const cn = [[1, 1], [1, -1], [-1, -1], [-1, 1]].map(([x, z]) => [x * F, z * F]);
  for (let k = 0; k < 4; k++) {
    const [ax, az] = cn[k], [bx, bz] = cn[(k + 1) % 4];
    for (const y of [y0, y1 - 0.15]) g.add(tube(new THREE.Vector3(ax, y, az), new THREE.Vector3(bx, y, bz), 0.13, blue));
    g.add(tube(new THREE.Vector3(ax, y0, az), new THREE.Vector3(ax, y1 - 0.15, az), 0.15, blue));
    g.add(tube(new THREE.Vector3(ax, y0, az), new THREE.Vector3(bx, y1 - 0.15, bz), 0.08, blue), tube(new THREE.Vector3(bx, y0, bz), new THREE.Vector3(ax, y1 - 0.15, az), 0.08, blue));
    g.add(tube(new THREE.Vector3(ax * 0.3, y1 - 0.15, az * 0.3), new THREE.Vector3(ax, y1 - 0.15, az), 0.1, blue));
  }
  // black pressure tanks lying on the frame round the tank's foot
  for (let k = 0; k < 4; k++) { const c = new THREE.CapsuleGeometry(0.32, 2.6, 6, 16); c.rotateZ(Math.PI / 2); c.translate(0, y1 - 0.55, F - 0.25); c.rotateY(k * Math.PI / 2); g.add(mesh(c, M.blackPlain)); }
  // the Merlin under the frame, its plumbing inside
  g.add(merlin(M, 0, 0, y0 + 0.4, 1.3, 0.48));
  g.add(mesh(cylY(0.5, y0 + 0.4, y1 - 0.2, 20), M.metal));
  // the legs: from each corner at the top of the frame out to a foot, a brace from the frame's foot, a small pad
  for (let k = 0; k < 4; k++) {
    const [ax, az] = cn[k], u = new THREE.Vector3(ax, 0, az).normalize();
    const t = new THREE.Vector3(ax, y1 - 0.15, az), f = u.clone().multiplyScalar(7.2).setY(0.25), b = new THREE.Vector3(ax, y0, az);
    g.add(tube(t, f, 0.2, alu), tube(b, f.clone().lerp(t, 0.2), 0.13, alu));
    const pad = new THREE.CylinderGeometry(0.42, 0.5, 0.22, 20); pad.translate(f.x, 0.11, f.z); g.add(mesh(pad, alu));
  }
  return g;
}
function f9v11(M) {
  const g = new THREE.Group(), R = 1.83;
  g.add(f9Stage(M, 41.9, { legs: true, soot: 0.04, stack: true, stackPos: 0.64 }));
  g.add(mesh(cylY(R, 41.9, 46.3, 72), M.white));                           // interstage (white on v1.1)
  g.add(mesh(cylY(R, 46.3, 54.9, 72), M.white));
  g.add(fairing(M, R, 2.6, 54.9, 6.4, 6.1, 1.0));
  return g;
}
function f9b5(M, opts = {}) {
  const g = new THREE.Group(), R = 1.83;
  g.add(f9Stage(M, 41.2, { legs: true, soot: opts.soot ?? 0.14, raceDark: true, logo: opts.logo ?? 'FALCON 9', flag: !opts.heavyCore, label: opts.label === undefined ? 'SPACEX' : opts.label, stack: true, stackPos: 0.66 }));
  if (opts.heavyCore) {
    const it = M.white.clone(); it.map = stageTex({ label: null, rings: 1, flag: true, logo: 'FH', h: 1024, flagPos: 0.62, logoSize: 0.07, flagScale: 1.1, soot: 0.05 });
    g.add(mesh(cylY(R, 42.5, 47.7, 72, true), it));
    g.add(mesh(cylY(R + 0.005, 41.2, 42.5, 72), M.black));
    for (let i = 0; i < 4; i++) g.add(gridFin(M, Math.PI / 4 + i * TAU / 4, R, 41.85, 1.0, 1.1));
  } else {
    g.add(mesh(cylY(R + 0.005, 41.2, 47.7, 72), M.black));                 // the black carbon interstage
    for (let i = 0; i < 4; i++) g.add(gridFin(M, Math.PI / 4 + i * TAU / 4, R, 46.6, 1.15, 1.4));
  }
  const s2 = M.white.clone(); s2.map = stageTex({ label: null, rings: 3 }); g.add(mesh(cylY(R, 47.7, 56.2, 72), s2));
  g.add(fairing(M, R, 2.6, 56.2, 6.0, 6.8, 1.0));
  return g;
}
function falconHeavy(M) {
  const g = new THREE.Group(), R = 1.83, gap = R * 2 + 0.35;
  g.add(f9b5(M, { soot: 0.22, label: null, heavyCore: true }));
  for (const s of [-1, 1]) {
    const side = new THREE.Group();
    side.add(f9Stage(M, 41.2, { legs: true, label: null, flag: false, soot: 0.45, raceDark: true }));
    side.add(mesh(cylY(R + 0.005, 41.2, 43.6, 72), M.black));
    for (let i = 0; i < 4; i++) side.add(gridFin(M, Math.PI / 4 + i * TAU / 4, R, 42.9, 1.15, 1.4));
    side.add(mesh(lathe(ogive(R, 43.6, 6.0, 0.12), 72), M.white));        // the side booster's nose cone
    // the struts tying it to the centre core, top and bottom
    for (const y of [3.2, 40.6]) side.add(mesh(boxAt(0.42, 0.42, 0.42, -s * (R + 0.15), y, 0), M.metal));
    side.position.x = s * gap; g.add(side);
  }
  return g;
}
// Starhopper as it hopped in July-August 2019 (after photos of the hops and on its pad): a stainless barrel about as
// tall as it is wide, standing high on three long legs. Each leg is a straight tube from just under the dome down and
// out to a small foot, with a big triangular sheet of wrinkled steel between the tube and the barrel, braced under the
// barrel; the lower legs are a dull tan. A low, bronze-tinted dome on top with white pressure tanks round its rim, a
// round hatch and pipe runs down the side, one Raptor under the open bottom.
function hopperTex(seed, opts = {}) {
  let sd = seed; const rnd = () => (sd = (sd * 16807) % 2147483647) / 2147483647;
  const { W = 2048, H = 1024, cols = 16, rows = 4, soot = 0.4, base = 216, spread = 26, rs = 50 } = opts;
  const blobs = []; for (let k = 0; k < 2600; k++) blobs.push({ x: rnd() * W, y: rnd() * H, r: 10 + rnd() * 34, s: rnd() < 0.5 ? 1 : -1, a: 0.025 + rnd() * 0.05 });
  const sheet = []; for (let c = 0; c < cols; c++) for (let r = 0; r < rows; r++) sheet.push({ c, r, v: rnd(), q: rnd() });
  const cells = (g, f) => sheet.forEach(s => f(s, s.c * W / cols, s.r * H / rows, W / cols + 1, H / rows + 1));
  const map = canvasTex(W, H, g => {
    cells(g, (s, x, y, w, h) => { const v = base + s.v * spread | 0; g.fillStyle = `rgb(${v},${v + 2},${v + 6})`; g.fillRect(x, y, w, h); });
    // the quilted wrinkles of thin sheet: soft light and dark dimples everywhere
    blobs.forEach(b => { const gr = g.createRadialGradient(b.x, b.y, 0, b.x, b.y, b.r), c = b.s > 0 ? '255,255,255' : '30,34,42'; gr.addColorStop(0, `rgba(${c},${b.a})`); gr.addColorStop(1, `rgba(${c},0)`); g.fillStyle = gr; g.fillRect(b.x - b.r, b.y - b.r, 2 * b.r, 2 * b.r); });
    // faint warm streaks down the sheets (heat tint and run-off), as in the photos
    for (let k = 0; k < 90; k++) { const x = rnd() * W, w = 4 + rnd() * 16, gr = g.createLinearGradient(0, 0, 0, H); gr.addColorStop(0, 'rgba(170,120,70,0)'); gr.addColorStop(0.5, `rgba(170,120,70,${0.05 + rnd() * 0.08})`); gr.addColorStop(1, 'rgba(170,120,70,0.02)'); g.fillStyle = gr; g.fillRect(x, rnd() * H * 0.4, w, H); }
    // seams: vertical between sheets, a row of welds round it
    for (let c = 0; c < cols; c++) { const x = c * W / cols; g.fillStyle = 'rgba(80,76,70,0.45)'; g.fillRect(x, 0, 3, H); g.fillStyle = 'rgba(255,255,255,0.35)'; g.fillRect(x + 3, 0, 2, H); }
    for (let r = 1; r < rows; r++) { const y = r * H / rows; for (let x = 0; x < W; x += 9) { g.fillStyle = 'rgba(70,66,60,0.28)'; g.fillRect(x, y - 1, 5, 3); } }
    if (soot > 0) { const lo = g.createLinearGradient(0, H, 0, H * (1 - soot)); lo.addColorStop(0, 'rgba(110,88,62,0.32)'); lo.addColorStop(1, 'rgba(96,78,58,0)'); g.fillStyle = lo; g.fillRect(0, H * (1 - soot), W, H * soot);
      for (let k = 0; k < 120; k++) { const x = rnd() * W, l = 40 + rnd() * H * soot, gr = g.createLinearGradient(0, H, 0, H - l); gr.addColorStop(0, `rgba(60,48,36,${0.12 + rnd() * 0.25})`); gr.addColorStop(1, 'rgba(60,48,36,0)'); g.fillStyle = gr; g.fillRect(x, H - l, 3 + rnd() * 10, l); } }
  });
  const rough = canvasTex(W, H, g => {
    cells(g, (s, x, y, w, h) => { const v = 80 + s.q * rs | 0; g.fillStyle = `rgb(${v},${v},${v})`; g.fillRect(x, y, w, h); });
  }, false);
  const bump = canvasTex(W, H, g => {
    g.fillStyle = '#808080'; g.fillRect(0, 0, W, H);
    blobs.forEach(b => { const gr = g.createRadialGradient(b.x, b.y, 0, b.x, b.y, b.r), c = b.s > 0 ? 255 : 0; gr.addColorStop(0, `rgba(${c},${c},${c},${b.a * 2.4})`); gr.addColorStop(1, `rgba(${c},${c},${c},0)`); g.fillStyle = gr; g.fillRect(b.x - b.r, b.y - b.r, 2 * b.r, 2 * b.r); });
  }, false);
  return { map, rough, bump };
}
function domeTex() {   // the dome: gores meeting at the top, bronze-tinted from heat, a little streaky
  let sd = 11; const rnd = () => (sd = (sd * 16807) % 2147483647) / 2147483647;
  return canvasTex(2048, 512, (g, W, H) => {
    const n = 24; for (let k = 0; k < n; k++) { const v = 150 + rnd() * 40 | 0; g.fillStyle = `rgb(${v + 14},${v + 4},${v - 8})`; g.fillRect(k * W / n, 0, W / n + 1, H); }
    for (let k = 0; k < 400; k++) { const x = rnd() * W, w = 6 + rnd() * 30, gr = g.createLinearGradient(x, 0, x + w, 0), c = rnd() < 0.5 ? '255,245,230' : '40,30,24'; gr.addColorStop(0, `rgba(${c},0)`); gr.addColorStop(0.5, `rgba(${c},${0.06 + rnd() * 0.1})`); gr.addColorStop(1, `rgba(${c},0)`); g.fillStyle = gr; g.fillRect(x, rnd() * H * 0.5, w, H); }
    for (let k = 0; k < n; k++) { g.fillStyle = 'rgba(60,50,40,0.6)'; g.fillRect(k * W / n, 0, 3, H); }
    g.fillStyle = 'rgba(60,50,40,0.5)'; g.fillRect(0, H * 0.45, W, 4);
  });
}
function starhopper(M) {
  const g = new THREE.Group(), R = 4.5, yB = 4.3, yT = 15.6, yD = 17.6, env = M.white.envMap;
  const T = hopperTex(7, { soot: 0.2 }), TF = hopperTex(19, { W: 1024, H: 1024, cols: 6, rows: 5, soot: 0.15, spread: 8, rs: 14 });
  [TF.map, TF.rough, TF.bump].forEach(t => { t.wrapS = t.wrapT = THREE.RepeatWrapping; });
  const steel = new THREE.MeshStandardMaterial({ color: 0xffffff, map: T.map, roughnessMap: T.rough, bumpMap: T.bump, bumpScale: 0.45, metalness: 1, roughness: 1, envMap: env, envMapIntensity: 1.45, side: THREE.DoubleSide });
  const finSteel = new THREE.MeshStandardMaterial({ color: 0xffffff, map: TF.map, roughnessMap: TF.rough, bumpMap: TF.bump, bumpScale: 0.6, metalness: 1, roughness: 1, envMap: env, envMapIntensity: 1.45, side: THREE.DoubleSide });
  const tube = new THREE.MeshStandardMaterial({ color: 0xd8dade, metalness: 1, roughness: 0.38, envMap: env, envMapIntensity: 1.35 });
  const tan = new THREE.MeshStandardMaterial({ color: 0xc8b796, metalness: 0.25, roughness: 0.62, envMap: env, envMapIntensity: 0.7 });
  const dome = new THREE.MeshStandardMaterial({ color: 0xffffff, map: domeTex(), metalness: 1, roughness: 0.5, envMap: env, envMapIntensity: 1.3, side: THREE.DoubleSide });
  const white = new THREE.MeshStandardMaterial({ color: 0xf1f2f2, metalness: 0.05, roughness: 0.55, envMap: env, envMapIntensity: 0.6 });
  const darkM = new THREE.MeshStandardMaterial({ color: 0x2c2e33, metalness: 0.5, roughness: 0.55, envMap: env, envMapIntensity: 0.6 });
  const tubeBetween = (p, q, r0, r1, mat, seg = 20) => {
    const d = q.clone().sub(p), L = d.length(), c = new THREE.CylinderGeometry(r1, r0, L, seg); c.translate(0, L / 2, 0);
    const m = mesh(c, mat); m.position.copy(p); m.quaternion.setFromUnitVectors(new THREE.Vector3(0, 1, 0), d.normalize()); return m;
  };
  // the barrel, open at the bottom; a heavier ring at its foot and at its top
  g.add(mesh(cylY(R, yB, yT, 128, true), steel));
  g.add(mesh(cylY(R + 0.04, yB, yB + 0.3, 128), darkM));
  g.add(mesh(cylY(R + 0.05, yT - 0.12, yT + 0.12, 128), tube));
  // the dome: low and wide (2.1 m), a small fitting on top
  const dp = []; for (let k = 0; k <= 16; k++) { const t = k / 16; dp.push([R * Math.cos(t * Math.PI / 2), yT + (yD - yT) * Math.sin(t * Math.PI / 2)]); }
  g.add(mesh(lathe(dp, 128), dome));
  g.add(mesh(cylY(0.45, yD - 0.1, yD + 0.25, 24), tube), mesh(boxAt(0.5, 0.3, 0.3, 0.6, yD - 0.05, 0.2), tube));
  // white pressure tanks lying round the dome's rim, pointing up its slope
  for (let k = 0; k < 7; k++) {
    const a = -0.7 + k * TAU / 7, rr = R * 0.8, yy = yT + (yD - yT) * Math.sin(Math.acos(rr / R)) + 0.22;
    const c = new THREE.CapsuleGeometry(0.27, 0.9, 6, 16); c.rotateX(Math.PI / 2 + 0.66); c.translate(0, yy, rr);
    c.rotateY(a); g.add(mesh(c, white));
  }
  // under the barrel: the dark thrust structure and the Raptor's bell
  const bay = new THREE.CircleGeometry(R - 0.05, 64); bay.rotateX(Math.PI / 2); bay.translate(0, yB + 0.8, 0); g.add(mesh(bay, darkM));
  const prof = []; for (let i = 0; i <= 18; i++) { const t = i / 18; prof.push([0.3 + (0.66 - 0.3) * Math.pow(t, 0.6), yB + 0.7 - t * 1.8]); }
  const bell = new THREE.LatheGeometry(prof.map(([r, y]) => new THREE.Vector2(r, y)), 48); g.add(mesh(bell, M.engine));
  const inner = bell.clone(); inner.scale(0.96, 1, 0.96); g.add(mesh(inner, M.engineIn));
  g.add(mesh(cylY(0.55, yB + 0.6, yB + 0.85, 32), M.engine));
  for (let k = 0; k < 6; k++) { const a = k / 6 * TAU; g.add(tubeBetween(new THREE.Vector3(Math.sin(a) * 0.5, yB + 0.75, Math.cos(a) * 0.5), new THREE.Vector3(Math.sin(a) * (R - 0.3), yB + 0.3, Math.cos(a) * (R - 0.3)), 0.12, 0.12, darkM, 10)); }
  // three legs: one toward the viewer, the other two out to the sides with their sheets seen as triangles (as in the hop photos)
  const yTop = 15.1, rF = 10.3, yF = 0.35;
  for (let i = 0; i < 3; i++) {
    const a = -0.42 + i * TAU / 3, leg = new THREE.Group();
    const top = new THREE.Vector3(0, yTop, R + 0.25), foot = new THREE.Vector3(0, yF + 0.3, rF);
    const at = y => top.clone().lerp(foot, (yTop - y) / (yTop - foot.y));
    const knee = at(yB);
    leg.add(tubeBetween(knee, top, 0.5, 0.42, tube));                      // the upper tube, polished
    leg.add(tubeBetween(foot, knee, 0.56, 0.5, tan));                      // the lower leg, tan
    leg.add(mesh(cylY(0.62, yF - 0.35, yF + 0.35, 24).translate(0, 0, rF), tan));   // the foot
    leg.add(mesh(cylY(0.5, yTop - 0.4, yTop + 0.5, 20).translate(0, 0, R + 0.2), tube));   // its mount under the dome
    // the triangular sheet: the barrel's wall from the mount down to the barrel's foot, out to the tube
    const sh = new THREE.Shape(); sh.moveTo(0, yTop - 0.2); sh.lineTo(0, yB); sh.lineTo(knee.z - R - 0.25, yB); sh.lineTo(0.15, yTop - 0.4); sh.lineTo(0, yTop - 0.2);
    const pg = new THREE.ShapeGeometry(sh, 4); const pos = pg.attributes.position, uv = pg.attributes.uv;
    for (let k = 0; k < pos.count; k++) uv.setXY(k, pos.getX(k) / 6, pos.getY(k) / 7);
    pg.rotateY(-Math.PI / 2); pg.translate(0, 0, R); leg.add(mesh(pg, finSteel));
    // its frame: a beam along the bottom edge, and the braces under the barrel to the lower leg
    leg.add(tubeBetween(new THREE.Vector3(0, yB, R - 0.1), knee, 0.16, 0.16, tan, 12));
    const mid = at(2.2);
    for (const s of [-1, 1]) leg.add(tubeBetween(new THREE.Vector3(s * 1.4, yB + 0.05, R - 0.6), mid, 0.17, 0.17, tan, 12));
    leg.add(tubeBetween(new THREE.Vector3(0, yB + 0.05, R * 0.35), knee.clone().lerp(mid, 0.5), 0.15, 0.15, tan, 12));
    leg.rotation.y = a; g.add(leg);
  }
  // on the side: a round hatch near the top, pipe runs and cable trays down the barrel
  const hatch = new THREE.CylinderGeometry(0.55, 0.55, 0.12, 32); hatch.rotateX(Math.PI / 2); hatch.translate(0, 13.6, R + 0.05); hatch.rotateY(0.2); g.add(mesh(hatch, dome));
  const ring = new THREE.TorusGeometry(0.55, 0.06, 8, 32); ring.translate(0, 13.6, R + 0.12); ring.rotateY(0.2); g.add(mesh(ring, tube));
  const runs = [[0.55, 15.2, 6.0, 0.07], [0.62, 14.8, 5.0, 0.05], [-0.25, 15.2, 7.5, 0.06], [-0.32, 12.0, 5.2, 0.05], [1.9, 15.2, 5.0, 0.08], [2.0, 13.0, 6.0, 0.05], [-2.6, 15.0, 5.5, 0.07]];
  runs.forEach(([a, y0, y1, r]) => { const p = cylY(r, y1, y0, 8); p.translate(0, 0, R + 0.12); p.rotateY(a); g.add(mesh(p, tube)); });
  for (const [a, y] of [[0.55, 9.5], [0.55, 6.0], [-0.25, 7.5]]) { const jog = new THREE.BoxGeometry(0.9, 0.1, 0.1); jog.translate(-0.45, y, R + 0.12); jog.rotateY(a); g.add(mesh(jog, tube)); }
  const tray = boxAt(0.32, 9.0, 0.16, 0, 10.4, R + 0.1); tray.rotateY(0.72); g.add(mesh(tray, tube));
  for (const [a, y] of [[0.4, 12.4], [0.45, 11.6], [0.85, 12.9]]) { const bx = boxAt(0.4, 0.3, 0.12, 0, y, R + 0.08); bx.rotateY(a); g.add(mesh(bx, darkM)); }
  return g;
}

// ---------------------------------------------------------------- Starship (the 3D Starship's own model)
let ssSrc = null;
async function starshipModel(renderer, version) {
  if (!ssSrc) ssSrc = await (await fetch(new URL('../starship-3d.html', import.meta.url), { cache: 'no-cache' })).text();
  const cut = (a, b) => { const i = ssSrc.indexOf(a), j = i < 0 ? -1 : ssSrc.indexOf(b, i + a.length); if (i < 0 || j < 0) throw new Error('starship-3d.html: no ' + a); return ssSrc.slice(i, j); };
  const code = [cut('const V2 = THREE.Vector2', '// ----------------'), cut('const FINITE_GLSL', 'const clampPass'), cut('// @model-begin', '// @model-end')].join('\n');
  const run = new Function('THREE', 'mergeGeometries', 'renderer', 'scene', 'isMobile', 'maxAniso', 'location',
    code + '\nreturn { groups, parts, fluids, engines, deployer, R };');
  const q = new URLSearchParams(); q.set('s', version === 3 ? '42' : version === 2 ? '35' : '28'); q.set('b', version === 3 ? '20' : version === 2 ? '14' : '10');
  return run(THREE, mergeGeometries, renderer, new THREE.Scene(), false, renderer.capabilities.getMaxAnisotropy(), { search: '?' + q.toString() });
}
async function starship(renderer, M, version) {
  const ss = await starshipModel(renderer, version), g = new THREE.Group();
  const ship = new THREE.Group(), booster = new THREE.Group();
  ss.groups.forEach(gr => (gr.userData.ship ? ship : booster).add(gr));
  g.add(booster, ship);
  // as the launch sim does: what's inside the hull (tanks, propellant, the satellites and their dispenser) and the 3D
  // page's own engine flames go - only the outside is seen here; the inside parts seen through the open ends stay
  const innerMats = new Set();
  Object.values(ss.parts).forEach(p => Object.entries(p.cache || {}).forEach(([k, mt]) => { if (k.includes('@in')) innerMats.add(mt); }));
  const drop = new Set(ss.fluids || []);
  (ss.engines || []).forEach(en => drop.add(en.e));
  if (ss.deployer) { ss.deployer.sats.forEach(x => drop.add(x)); if (ss.deployer.car) drop.add(ss.deployer.car); }
  drop.forEach(o => { if (o && o.parent) o.parent.remove(o); });
  const SEEN = (o) => /^(LOX aft dome|Ship engine shield|Thrust structure)$/.test(o.userData.label || '') || (o.userData.label === 'Forward dome' && o.userData.part === 'b-ch4');
  const kill = [];
  g.traverse(o => {
    if (!o.isMesh) return;
    const mats = Array.isArray(o.material) ? o.material : [o.material];
    if (mats.every(mt => mt.isShaderMaterial || mt.visible === false) || (mats.every(mt => innerMats.has(mt)) && !SEEN(o))) kill.push(o);
  });
  kill.forEach(o => { if (o.parent) o.parent.remove(o); });
  if (version < 3) blockOne(g, ship, version);
  return { g, ship, booster, ss };
}
// V1 and V2 (Block 1 boosters, 71 m): four smaller grid fins in place of V3's three big ones, and the separate vented
// hot-stage ring on top of the booster in place of the integrated truss; the ship sits on that ring. V1's ship is
// 50.3 m, V2's 52.1 m (V3's length).
const B_TOP = 68.8, S0_V3 = 72.3, HSR_TOP = 71.0;
function blockOne(g, ship, version) {
  let steelMat = null, finMat = null; const kill = [];
  g.traverse(o => {
    if (!o.isMesh) return; const l = o.userData.label || '';
    if (l === 'Methane tank') steelMat = o.material;
    if (/^Grid fin \d/.test(l)) { finMat = o.material; kill.push(o); }
    if (/^Hot-stage truss/.test(l)) kill.push(o);
  });
  kill.forEach(o => o.parent && o.parent.remove(o));
  const R = 4.5, TEXM = 1.83 * 8, add = (geo, mat) => { const m = mesh(geo, mat); g.add(m); return m; };
  const steelCyl = (r, y0, y1, a0 = 0, al = TAU) => {
    const c = new THREE.CylinderGeometry(r, r, y1 - y0, 128, 1, true, a0, al); c.translate(0, (y0 + y1) / 2, 0);
    const uv = c.attributes.uv, p = c.attributes.position;   // the tank's texture scale: u once round, v per 8 rings
    for (let i = 0; i < uv.count; i++) uv.setXY(i, a0 / TAU + uv.getX(i) * al / TAU, p.getY(i) / TEXM);
    return c;
  };
  // the hot-stage ring: a short steel drum with a row of tall vents round it, each under a sloped deflector hood,
  // and a dome on top that shields the booster's forward dome from the ship's engines
  const y0 = B_TOP, y1 = HSR_TOP, N = 18, vH = 1.25, vy = y0 + 0.45, gapA = TAU / N * 0.45;
  for (let i = 0; i < N; i++) add(steelCyl(R, vy, vy + vH, i / N * TAU + gapA / 2, TAU / N - gapA), steelMat);   // the posts between vents
  add(steelCyl(R, y0, vy), steelMat); add(steelCyl(R, vy + vH, y1), steelMat);
  add(cylY(R - 0.35, vy - 0.05, vy + vH + 0.05, 96), M_DARK());                                                        // the dark inside, seen through the vents
  for (let i = 0; i < N; i++) {
    const a = i / N * TAU, hood = new THREE.BoxGeometry(2 * Math.PI * R / N * 0.5, 0.08, 0.55);
    hood.rotateX(0.5); hood.translate(0, vy + vH + 0.05, R + 0.2); hood.rotateY(a); add(hood, steelMat);
  }
  const dome = []; for (let k = 0; k <= 12; k++) { const t = k / 12; dome.push([(R - 0.05) * Math.cos(t * Math.PI / 2), y1 - 0.2 + 1.3 * Math.sin(t * Math.PI / 2)]); }
  add(lathe(dome, 96), steelMat);
  // four grid fins near the top of the booster, sticking straight out, in an X seen from the side
  for (let i = 0; i < 4; i++) {
    const a = Math.PI / 4 + i * TAU / 4, fin = new THREE.Group();
    const w = 3.6, L = 2.9, t = 0.55, bars = [];
    bars.push(boxAt(L, t, 0.12, L / 2, 0, w / 2), boxAt(L, t, 0.12, L / 2, 0, -w / 2), boxAt(0.12, t, w, L, 0, 0), boxAt(0.3, t + 0.2, w + 0.2, 0, 0, 0));
    for (let k = -7; k <= 7; k++) for (const s of [-1, 1]) {   // the diamond lattice: bars at ±45° clipped to the frame (kept inside by their length)
      const c = k * 0.42, len = Math.min(L, w) * 1.414; const b = new THREE.BoxGeometry(len, t, 0.05); b.rotateY(s * Math.PI / 4); b.translate(L / 2 + c * 0.7071, 0, -s * c * 0.7071);
      const pos = b.attributes.position; for (let j = 0; j < pos.count; j++) { pos.setX(j, Math.min(L, Math.max(0.15, pos.getX(j)))); pos.setZ(j, Math.min(w / 2, Math.max(-w / 2, pos.getZ(j)))); }
      bars.push(b);
    }
    const geo = mergeGeometries(bars.map(b => b.index ? b.toNonIndexed() : b).map(b => { b.deleteAttribute('uv'); return b; }));
    const m = mesh(geo, finMat || M_DARK()); m.position.x = R; fin.add(m);
    fin.rotation.y = a - Math.PI / 2; fin.position.y = 67.0; g.add(fin);
  }
  // V1's forward flaps: larger, and out on the sides of the nose on the heat shield's edge, in line with the aft flaps
  // (V2 moved them toward the leeward side and made them smaller - as the 3D Starship's model has them)
  if (version === 1) {
    const holders = new Map();
    ship.traverse(o => {
      const l = o.isMesh && o.userData.label || '', side = l.startsWith('Forward flap (left)') ? 1 : l.startsWith('Forward flap (right)') ? -1 : 0;
      if (!side) return;
      let h = o; while (h.parent && h.parent.userData.ship === undefined) h = h.parent;   // up to the flap's holder in the nose group
      holders.set(h, side);
    });
    holders.forEach((side, h) => {
      h.rotation.y -= side * 0.3;
      const inner = h.children[0] && h.children[0].children.find(c => c.isGroup);
      if (inner) inner.scale.setScalar(1.18);
    });
  }
  // the ship: on the ring, V1 shorter
  const k = version === 1 ? 50.3 / 52.1 : 1;
  ship.scale.y = k; ship.position.y = HSR_TOP - S0_V3 * k;
}
let _dark = null; const M_DARK = () => _dark || (_dark = new THREE.MeshStandardMaterial({ color: 0x15171a, roughness: 0.7, metalness: 0.3, side: THREE.DoubleSide }));

// build one vehicle (metres, base at 0)
export async function buildVehicle(id, renderer, envMap) {
  const g = await buildRaw(id, renderer, envMap), v = VEHICLES.find(x => x.id === id);
  // the published height, engine bells to tip, standing on y = 0
  g.updateMatrixWorld(true); const b = new THREE.Box3().setFromObject(g, true), k = v.h / (b.max.y - b.min.y);
  const out = new THREE.Group(); g.position.y = -b.min.y; out.add(g); out.scale.y = k; return out;
}
async function buildRaw(id, renderer, envMap) {
  const M = materials(envMap);
  switch (id) {
    case 'falcon1': return falcon1(M);
    case 'f9v10': return f9v10(M);
    case 'grasshopper': return grasshopper(M);
    case 'f9v11': return f9v11(M);
    case 'f9b5': return f9b5(M);
    case 'fh': return falconHeavy(M);
    case 'starhopper': return starhopper(M);
    case 'ssv1': case 'ssv2': case 'ssv3': { const r = await starship(renderer, M, id === 'ssv1' ? 1 : id === 'ssv2' ? 2 : 3); return r.g; }
  }
  throw new Error('no vehicle ' + id);
}
// the studio light the models are seen in (its reflections): soft boxes all round, like the 3D Starship's
export function studioEnv(renderer) {
  const env = new THREE.Scene(), O = new THREE.Vector3();
  env.add(new THREE.Mesh(new THREE.SphereGeometry(80, 32, 16), new THREE.MeshBasicMaterial({ color: 0x0b0d12, side: THREE.BackSide })));
  const panel = (w, h, k, color, pos) => { const m = new THREE.Mesh(new THREE.PlaneGeometry(w, h), new THREE.MeshBasicMaterial({ color: new THREE.Color(color).multiplyScalar(k), side: THREE.DoubleSide })); m.position.copy(pos); m.lookAt(O); env.add(m); };
  panel(60, 26, 1.1, 0xffffff, new THREE.Vector3(0, 55, 0));
  panel(70, 40, 1.4, 0xffffff, new THREE.Vector3(-10, 18, 60));
  panel(130, 4.5, 2.6, 0xe4ecff, new THREE.Vector3(0, 24, -58));
  panel(4, 90, 2.6, 0xffffff, new THREE.Vector3(-62, 15, 12));
  panel(4, 90, 2.0, 0xcfdcff, new THREE.Vector3(62, 15, -14));
  panel(110, 110, 0.8, 0xd4dcea, new THREE.Vector3(0, -50, 0));
  const pm = new THREE.PMREMGenerator(renderer), t = pm.fromScene(env, 0.02).texture; pm.dispose(); return t;
}
