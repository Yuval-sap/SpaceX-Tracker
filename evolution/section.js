// "SpaceX - From history to present", the info tab's section after the milestones: every SpaceX rocket to scale in one
// chart (pictures from img/, made by render.html), and each one in live 3D in a viewer over the page (viewer.js and
// vehicles.js, loaded the first time a rocket is opened). Its words follow the site's language - the site's own timer
// calls window.applyEvolutionLang - and its units the site's (feet and pounds in English, metres elsewhere); its
// colours come from the site's classes and section.css, so it changes with the site's mode.
import { VEHICLES } from './data.js';

const T = window.EVO_I18N || { en: {} };
const LOCALES = { en: 'en-US', he: 'he-IL', ar: 'ar', es: 'es', fr: 'fr', de: 'de', ru: 'ru', zh: 'zh-CN', it: 'it', cs: 'cs', sv: 'sv', nl: 'nl', da: 'da', pt: 'pt-BR', pl: 'pl', hi: 'hi', tr: 'tr' };
const norm = c => { c = String(c || '').toLowerCase().split(/[-_]/)[0]; if (c === 'iw') c = 'he'; return T[c] ? c : 'en'; };
const asset = p => new URL(p, import.meta.url).href;
// the site takes "Block 5" out of whatever it adds to the page (a script at the end of index.html, for the launches'
// names), rewriting any element that holds it; here it is part of the rocket's name, so the words are kept apart
// by an invisible joiner - in its text and its label alike
const shown = s => s.replace(/Block 5/, 'Block‌ 5');
const rtlNow = () => document.documentElement.dir === 'rtl';
const MAXM = 130;   // the scale's top, in metres

let lang = null, tr = k => '', nf = null, IMPERIAL = false, STATS = {};
const metres = v => IMPERIAL ? `${nf.format(Math.round(v * 3.28084))} ${tr('ft')}` : `${nf.format(v)} ${tr('m')}`;
const mass = kg => IMPERIAL ? `${nf.format(Math.round(kg * 2.20462 / (kg < 1000 ? 10 : 100)) * (kg < 1000 ? 10 : 100))} ${tr('lb')}`
  : kg < 1000 ? `${nf.format(kg)} ${tr('kg')}` : `${nf.format(Math.round(kg / 100) / 10)} ${tr('t')}`;

const chart = document.getElementById('evo-chart');
const sec = document.getElementById('evo-sec');
function init() {
  chart.innerHTML = `<div class="evo-axis" aria-hidden="true"></div><div class="evo-scroller"><div class="evo-plot"><div class="evo-sky"></div><div class="evo-grid"></div><div class="evo-ground"></div><div class="evo-row"></div></div></div>`;
  const row = chart.querySelector('.evo-row');
  VEHICLES.forEach((v, i) => {
    const b = document.createElement('button'); b.type = 'button'; b.className = 'evo-veh'; b.dataset.i = i;
    b.style.setProperty('--h', (v.px / 12).toFixed(3));   // the picture's height in metres (12 px per metre, a little room above the tip)
    b.innerHTML = `<span class="evo-pic"><img src="${asset('img/' + v.id + '.webp')}" alt="" loading="lazy" decoding="async"></span>`
      + `<span class="evo-lab"><span class="evo-yr font-orbitron text-emerald-400">${v.year}</span><span class="evo-nm text-white"></span><span class="evo-ht text-zinc-400"></span>`
      + (v.test ? `<span class="evo-tag text-zinc-400 border border-zinc-800"></span>` : '') + `</span>`;
    b.querySelector('.evo-nm').textContent = shown(v.name);
    row.appendChild(b);
  });
  // one listener for all of them, on the chart's own box from the page's markup (anything inside it may be redrawn)
  chart.addEventListener('click', e => { const b = e.target.closest('.evo-veh'); if (b) openViewer(+b.dataset.i); });
  buildModal();
  window.applyEvolutionLang = applyLang;
  applyLang(document.documentElement.lang || 'en');
  addEventListener('resize', layout);
  // the chart sits in a tab that may be hidden at first: laid out again once it has a size
  new ResizeObserver(() => layout()).observe(chart);
  fetch(asset('stats.json'), { cache: 'no-cache' }).then(r => r.ok ? r.json() : null)
    .then(j => { if (j && j.vehicles) { STATS = j.vehicles; if (cur >= 0) fillInfo(cur); } }).catch(() => {});
}

function applyLang(l) {
  const key = norm(l), imp = key === 'en';
  if (key === lang && imp === IMPERIAL) return;
  lang = key; IMPERIAL = imp;
  const d = T[lang] || T.en; tr = k => d[k] || T.en[k] || '';
  nf = new Intl.NumberFormat(LOCALES[lang] || 'en-US', { maximumFractionDigits: 1 });
  const set = (id, s) => { const el = document.getElementById(id); if (el && el.textContent !== s) el.textContent = s; };
  set('i18n-evo-title', tr('secTitle')); set('i18n-evo-hint', tr('hint')); set('evo-note', tr('note'));
  chart.querySelectorAll('.evo-veh').forEach(b => {
    const v = VEHICLES[+b.dataset.i];
    b.setAttribute('aria-label', `${shown(v.name)}, ${v.year}, ${metres(v.h)}. ${tr('view3d')}`);
    b.querySelector('.evo-pic').dataset.h = metres(v.h);
    b.querySelector('.evo-ht').textContent = metres(v.h);
    const tag = b.querySelector('.evo-tag'); if (tag) tag.textContent = tr('test');
  });
  modal.querySelectorAll('[data-t]').forEach(el => { el.textContent = tr(el.dataset.t); });
  modal.querySelector('.evo-x').setAttribute('aria-label', tr('close'));
  layout();
  if (cur >= 0) fillInfo(cur);
}

function layout() {
  const pic = chart.querySelector('.evo-pic'); if (!pic || !nf) return;
  const plotH = parseFloat(getComputedStyle(pic).height); if (!plotH) return;
  const pxm = plotH / MAXM; chart.style.setProperty('--pxm', pxm.toFixed(4));
  const stepM = IMPERIAL ? 50 / 3.28084 : 20, stepN = IMPERIAL ? 50 : 20, unit = tr(IMPERIAL ? 'ft' : 'm');
  chart.style.setProperty('--gridStep', (stepM * pxm).toFixed(2) + 'px');
  const axis = chart.querySelector('.evo-axis'), labels = [];
  for (let k = 0; k * stepM <= MAXM - 8; k++) labels.push([k * stepM * pxm, `${nf.format(k * stepN)} ${unit}`]);
  const sig = labels.map(l => l.join(':')).join('|'); if (axis.dataset.sig === sig) return; axis.dataset.sig = sig;
  axis.innerHTML = ''; labels.forEach(([y, t]) => { const s = document.createElement('span'); s.style.bottom = y + 'px'; s.textContent = t; axis.appendChild(s); });
}

// ---- the 3D viewer
let modal, stage, loadEl, prevB, nextB, V = null, cur = -1;
function buildModal() {
  modal = document.createElement('div'); modal.id = 'evo-modal'; modal.hidden = true;
  modal.className = 'notranslate'; modal.setAttribute('translate', 'no'); modal.setAttribute('role', 'dialog'); modal.setAttribute('aria-modal', 'true');
  modal.innerHTML = `<div class="evo-box bg-zinc-950 border border-zinc-800">
    <button class="evo-x bg-zinc-900 border-zinc-800 text-white" type="button">×</button>
    <div class="evo-stage"><div class="evo-load text-zinc-400"><i></i><span data-t="loading"></span></div><div class="evo-drag text-zinc-400" data-t="drag"></div></div>
    <div class="evo-info bg-zinc-900 border-zinc-800">
      <div class="evo-myr font-orbitron text-emerald-400" data-f="year"></div>
      <h2 class="font-orbitron text-white" data-f="name"></h2>
      <span class="evo-mtag text-zinc-400 border border-zinc-800" data-t="test" data-f="test"></span>
      <p class="evo-mnote text-zinc-300" data-f="note"></p>
      <div class="evo-stats">
        <div class="evo-stat bg-zinc-950 border border-zinc-800"><b class="text-zinc-400" data-t="height"></b><span class="text-white" data-f="h"></span></div>
        <div class="evo-stat bg-zinc-950 border border-zinc-800"><b class="text-zinc-400" data-f="dl"></b><span class="text-white" data-f="d"></span></div>
        <div class="evo-stat bg-zinc-950 border border-zinc-800"><b class="text-zinc-400" data-t="engines"></b><span class="text-white" data-f="e" dir="ltr"></span></div>
        <div class="evo-stat bg-zinc-950 border border-zinc-800"><b class="text-zinc-400" data-t="first"></b><span class="text-white" data-f="y"></span></div>
        <div class="evo-stat bg-zinc-950 border border-zinc-800"><b class="text-zinc-400" data-f="ll"></b><span class="text-white" data-f="l"></span><small class="text-zinc-400" data-f="ls"></small></div>
        <div class="evo-stat bg-zinc-950 border border-zinc-800"><b class="text-zinc-400" data-f="sl"></b><span class="text-white" data-f="s"></span><small class="text-zinc-400" data-f="ss"></small></div>
        <div class="evo-stat wide bg-zinc-950 border border-zinc-800"><b class="text-zinc-400" data-t="payload"></b><span class="text-white" data-f="p"></span><small class="text-zinc-400" data-f="ps"></small></div>
        <div class="evo-stat wide bg-zinc-950 border border-zinc-800"><b class="text-zinc-400" data-f="cl"></b><span class="text-white" data-f="c"></span><small class="text-zinc-400" data-f="cs"></small></div>
      </div>
      <div class="evo-nav"><button type="button" class="bg-zinc-950 border-zinc-800 text-white" data-nav="-1"></button><button type="button" class="bg-zinc-950 border-zinc-800 text-white" data-nav="1"></button></div>
    </div></div>`;
  document.body.appendChild(modal);
  stage = modal.querySelector('.evo-stage'); loadEl = modal.querySelector('.evo-load');
  [prevB, nextB] = modal.querySelectorAll('[data-nav]');
  modal.querySelector('.evo-x').addEventListener('click', closeViewer);
  modal.addEventListener('click', e => {
    if (e.target === modal) return closeViewer();
    const n = e.target.closest('[data-nav]'); if (n && cur >= 0 && VEHICLES[cur + +n.dataset.nav]) openViewer(cur + +n.dataset.nav);
  });
  addEventListener('keydown', e => {
    if (modal.hidden) return;
    if (e.key === 'Escape') closeViewer();
    if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') { const j = cur + ((e.key === 'ArrowRight') !== rtlNow() ? 1 : -1); if (VEHICLES[j]) openViewer(j); }
  });
  // the app's Back (and a phone browser's, with the app's look) closes the viewer first - the site's appHandleBack asks
  window.evoCloseViewer = () => { if (modal.hidden) return false; closeViewer(); return true; };
}
const F = f => modal.querySelector(`[data-f="${f}"]`);
function fillInfo(i) {
  const v = VEHICLES[i], st = STATS[v.id] || v;
  F('year').textContent = v.year;
  F('name').textContent = shown(v.name);
  F('test').hidden = !v.test;
  F('note').textContent = tr('n_' + v.id);
  F('h').textContent = metres(v.h);
  F('dl').textContent = tr(v.id === 'fh' ? 'width' : 'diameter');
  F('d').textContent = metres(v.d);
  F('e').textContent = v.eng;
  F('y').textContent = v.year;
  F('ll').textContent = tr(v.hops ? 'hops' : 'launches');
  F('l').textContent = nf.format(v.hops || st.ok);
  F('ls').textContent = v.hops ? '' : tr('ofLaunches').replace('{n}', nf.format(st.total));
  F('sl').textContent = tr(v.retired ? 'retired' : 'status');
  F('s').textContent = v.retired ? new Date(v.retired + 'T12:00:00Z').toLocaleDateString(LOCALES[lang] || 'en-US', { month: 'long', year: 'numeric' }) : tr('inService');
  F('ss').textContent = v.retired ? tr('lastFlight') : '';
  F('p').textContent = v.pay ? mass(v.pay) : tr('payNone');
  // the cost of a kilogram (a pound, in English) to low orbit: the launch's list price over its payload
  const usd = n => '\u2066$' + nf.format(n) + '\u2069';   // "$1,234" in every language, in one piece in RTL text
  F('cl').textContent = tr(IMPERIAL ? 'costLb' : 'costKg');
  if (v.pay && v.price) {
    const mid = v.priceHi ? (v.price + v.priceHi) / 2 : v.price, perKg = mid * 1e6 / v.pay, per = IMPERIAL ? perKg / 2.20462 : perKg;
    const round = per >= 10000 ? 100 : 10;
    F('c').textContent = tr(IMPERIAL ? 'perLb' : 'perKg').replace('{v}', (v.priceHi ? '~' : '') + usd(Math.round(per / round) * round));
    const m = x => '\u2066$' + nf.format(x) + 'M\u2069';
    F('cs').textContent = tr('listPrice').replace('{p}', v.priceHi ? '\u2066' + m(v.price) + '–' + m(v.priceHi) + '\u2069' : m(v.price)).replace('{y}', '\u2066' + v.priceYear + '\u2069');
  } else if (v.estKg) {   // not on sale: Elon Musk's estimate, marked as one
    const e = IMPERIAL ? v.estKg / 2.20462 : v.estKg;
    F('c').textContent = tr(IMPERIAL ? 'perLb' : 'perKg').replace('{v}', '~' + usd(Math.round(e * 10) / 10)) + '*';
    F('cs').textContent = tr('estNote');
  } else { F('c').textContent = v.pay ? tr('notForSale') : tr('payNone'); F('cs').textContent = ''; }
  F('ps').textContent = v.payReuse ? tr('payReuse').replace('{v}', mass(v.payReuse)) : v.payTarget ? tr('payTarget') : '';
  const p = VEHICLES[i - 1], n = VEHICLES[i + 1], back = rtlNow() ? '→' : '←', fwd = rtlNow() ? '←' : '→';
  prevB.disabled = !p; nextB.disabled = !n;
  prevB.textContent = p ? `${back} ${shown(p.name)}` : back;
  nextB.textContent = n ? `${shown(n.name)} ${fwd}` : fwd;
}
async function openViewer(i) {
  cur = i; fillInfo(i);
  if (modal.hidden) {
    modal.hidden = false; requestAnimationFrame(() => modal.classList.add('on'));
    document.documentElement.style.overflow = 'hidden';
    modal.querySelector('.evo-x').focus({ preventScroll: true });
  }
  loadEl.style.opacity = 1;
  try {
    if (!V) V = await import('./viewer.js');
    await V.show(stage, VEHICLES[i], () => cur === i);
    if (cur === i) loadEl.style.opacity = 0;
  } catch (e) { console.error(e); }
}
function closeViewer() {
  if (modal.hidden) return;
  modal.classList.remove('on'); document.documentElement.style.overflow = '';
  setTimeout(() => { modal.hidden = true; if (V) V.pause(); }, 200);
  const b = chart.querySelectorAll('.evo-veh')[cur]; if (b) b.focus({ preventScroll: true });
  cur = -1;
}

if (chart) init();
