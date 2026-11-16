// The vehicles of the evolution page: what the page shows about each, and how its picture was made.
// h: height in metres (bottom of the engines to the tip), d: diameter (Falcon Heavy: its full width), eng: engines,
// ok / total: successful and all launches as Launch Library counts that version (stats.json, updated by the
// scheduled Action, brings the flying ones up to date); hops: a test vehicle's free flights; retired: its last flight;
// price / priceHi / priceYear: a launch's list price, US$ million (Wikipedia; null: not on sale yet) - the card's cost
// per kg is that price over the payload; estKg: Elon Musk's estimate per kg (Starship, fully reusable - shown with an
// asterisk, as an estimate); pay: payload to low Earth orbit in kg (0: a test vehicle, none) - Wikipedia's figures; payReuse: with the booster landing;
// payTarget: the figure SpaceX gives for that Starship version; px: the height in pixels of its picture in img/ (rendered by render.html at 12 px per metre), az: the side it's seen from.
export const VEHICLES = [
  { id: 'falcon1', price: 7, priceYear: '2009', ok: 2, total: 5, retired: '2009-07-14', pay: 180, px: 260, eng: '1 × Merlin 1C', year: '2006', h: 21.3, d: 1.7, name: 'Falcon 1' },
  { id: 'f9v10', price: 54, priceHi: 59.5, priceYear: '2010–2013', ok: 4, total: 5, retired: '2013-03-01', pay: 9000, px: 579, eng: '9 × Merlin 1C', year: '2010', h: 47.8, d: 3.7, name: 'Falcon 9 v1.0' },
  { id: 'grasshopper', hops: 8, retired: '2013-10-07', pay: 0, px: 395, eng: '1 × Merlin 1D', year: '2012', h: 32.3, d: 3.7, name: 'Grasshopper', test: true },
  { id: 'f9v11', price: 61.2, priceYear: '2015', ok: 14, total: 15, retired: '2016-01-17', pay: 13150, px: 826, eng: '9 × Merlin 1D', year: '2013', h: 68.4, d: 3.7, name: 'Falcon 9 v1.1' },
  { id: 'f9b5', price: 74, priceYear: '2026', ok: 645, total: 646, pay: 22800, payReuse: 17400, px: 846, eng: '9 × Merlin 1D', year: '2018', h: 70, d: 3.7, name: 'Falcon 9 Block 5' },
  { id: 'fh', price: 150, priceYear: '2017–2022', ok: 14, total: 14, pay: 63800, px: 847, eng: '27 × Merlin 1D', year: '2018', h: 70, d: 12.2, name: 'Falcon Heavy' },
  { id: 'starhopper', hops: 2, retired: '2019-08-27', pay: 0, px: 224, eng: '1 × Raptor', year: '2019', h: 18, d: 9, name: 'Starhopper', test: true },
  { id: 'ssv1', price: null, estKg: 10, ok: 4, total: 6, retired: '2024-11-19', pay: 15000, payTarget: true, px: 1461, eng: '33 + 6 Raptor 2', year: '2023', h: 121.3, d: 9, name: 'Starship V1', az: 30 },
  { id: 'ssv2', price: null, estKg: 10, ok: 2, total: 5, retired: '2025-10-13', pay: 35000, payTarget: true, px: 1483, eng: '33 + 6 Raptor 2', year: '2025', h: 123.1, d: 9, name: 'Starship V2', az: 30 },
  { id: 'ssv3', price: null, estKg: 10, ok: 4, total: 4, pay: 100000, payTarget: true, px: 1498, eng: '33 + 6 Raptor 3', year: '2026', h: 124.4, d: 9, name: 'Starship V3', az: 30 },
  // future: not flown yet - its year shown as expected, in development, no launches (Elon Musk on X, Nov 2026: a ~70 m
  // ship, 9 engines - three more Raptor Vacuums - all higher-thrust Raptor 4s; reports: the booster's 33 Raptor 4s too, ~300 t each, ~10,000 t at liftoff, 200+ t to orbit)
  { id: 'ssv4', future: true, price: null, estKg: 10, ok: 0, total: 0, pay: 200000, payTarget: true, px: 1713, eng: '33 + 9 Raptor 4', year: '2027', h: 142.3, d: 9, name: 'Starship V4', az: 30 },
];
