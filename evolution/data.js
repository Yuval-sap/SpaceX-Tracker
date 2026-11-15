// The vehicles of the evolution page: what the page shows about each, and how its picture was made.
// h: height in metres (bottom of the engines to the tip), d: diameter (Falcon Heavy: its full width), eng: engines,
// ok / total: successful and all launches as Launch Library counts that version (stats.json, updated by the
// scheduled Action, brings the flying ones up to date); hops: a test vehicle's free flights; retired: its last flight;
// pay: payload to low Earth orbit in kg (0: a test vehicle, none) - Wikipedia's figures; payReuse: with the booster landing;
// payTarget: the figure SpaceX gives for that Starship version; px: the height in pixels of its picture in img/ (rendered by render.html at 12 px per metre), az: the side it's seen from.
export const VEHICLES = [
  { id: 'falcon1', ok: 2, total: 5, retired: '2009-07-14', pay: 180, px: 260, eng: '1 × Merlin 1C', year: '2006', h: 21.3, d: 1.7, name: 'Falcon 1' },
  { id: 'f9v10', ok: 4, total: 5, retired: '2013-03-01', pay: 9000, px: 579, eng: '9 × Merlin 1C', year: '2010', h: 47.8, d: 3.7, name: 'Falcon 9 v1.0' },
  { id: 'grasshopper', hops: 8, retired: '2013-10-07', pay: 0, px: 395, eng: '1 × Merlin 1D', year: '2012', h: 32.3, d: 3.7, name: 'Grasshopper', test: true },
  { id: 'f9v11', ok: 14, total: 15, retired: '2016-01-17', pay: 13150, px: 826, eng: '9 × Merlin 1D', year: '2013', h: 68.4, d: 3.7, name: 'Falcon 9 v1.1' },
  { id: 'f9b5', ok: 645, total: 646, pay: 22800, payReuse: 17400, px: 846, eng: '9 × Merlin 1D', year: '2018', h: 70, d: 3.7, name: 'Falcon 9 Block 5' },
  { id: 'fh', ok: 14, total: 14, pay: 63800, px: 847, eng: '27 × Merlin 1D', year: '2018', h: 70, d: 12.2, name: 'Falcon Heavy' },
  { id: 'starhopper', hops: 2, retired: '2019-08-27', pay: 0, px: 224, eng: '1 × Raptor', year: '2019', h: 18, d: 9, name: 'Starhopper', test: true },
  { id: 'ssv1', ok: 4, total: 6, retired: '2024-11-19', pay: 15000, payTarget: true, px: 1461, eng: '33 + 6 Raptor 2', year: '2023', h: 121.3, d: 9, name: 'Starship V1', az: 30 },
  { id: 'ssv2', ok: 2, total: 5, retired: '2025-10-13', pay: 35000, payTarget: true, px: 1483, eng: '33 + 6 Raptor 2', year: '2025', h: 123.1, d: 9, name: 'Starship V2', az: 30 },
  { id: 'ssv3', ok: 4, total: 4, pay: 100000, payTarget: true, px: 1498, eng: '33 + 6 Raptor 3', year: '2026', h: 124.4, d: 9, name: 'Starship V3', az: 30 },
];
