// (run by .github/workflows/update-mission-cards.yml, job "timeline-watch", every 4 hours)
// Watches that every launch about to take the hero has SpaceX's own status timeline (the hero's "status list").
//
// Opens the LIVE site in a headless Chrome and runs the site's own code for it - the same steps the page takes for the
// hero (fetchRealMissionTimeline): SpaceX's upcoming-launches tiles, the slug guesses, each mission page, the
// timeline parsed out of it and checked against the launch's name. Nothing here re-implements that matching, so what
// this checks is exactly what visitors get.
//
// A problem is:
//   - a launch in the next WINDOW_HOURS with no timeline found (SpaceX usually publishes the page days before), or
//   - a page SpaceX already lists in its upcoming tiles that the site matches to none of its launches, while the site
//     has a launch within TILE_NEAR_DAYS of the page's date - most likely that same launch, unmatched
//     (2026-10-10: "sda-t1tl-a" for "SDA Tranche 1 Transport Layer A" - the hero flew with no status list).
//     A page with no date yet, or none of the site's launches near its date, is only listed: SpaceX often publishes
//     a page before Launch Library has the launch at all (2026-10-13: "sl-15-16", not yet in Launch Library).
// Writes the findings to timeline-report.md and exits 1 when there is a problem (the workflow then alerts),
// 0 when all is well, and 0 with a note when the page itself could not be checked (no launch data loaded -
// e.g. Launch Library rate-limited this runner) so a passing hiccup does not raise a false alarm.
// SpaceX's server sometimes doesn't answer this runner for a while (2026-10-20: no upcoming tiles and a 404 for a
// page that had been up for 7 hours): a problem is checked again (ATTEMPTS times, ATTEMPT_WAIT_MS apart) before it
// counts, and a run in which SpaceX's upcoming tiles came back empty is a note, not an alarm - SpaceX always lists
// some, so an empty list means it didn't answer, not that the pages are missing.

import puppeteer from 'puppeteer-core';
import { writeFileSync } from 'node:fs';

const SITE = 'https://spacexfantracker.com/';
const WINDOW_HOURS = 48;
const TILE_NEAR_DAYS = 3;
const CHROME = process.env.CHROME_PATH || '/usr/bin/google-chrome';
const ATTEMPTS = 3;
const ATTEMPT_WAIT_MS = 90 * 1000;

const report = (lines) => writeFileSync('timeline-report.md', lines.join('\n') + '\n');

const browser = await puppeteer.launch({ executablePath: CHROME, headless: true, args: ['--no-sandbox', '--lang=en-US'] });
let exitCode = 0;
try {
    const page = await browser.newPage();
    await page.goto(SITE, { waitUntil: 'domcontentloaded', timeout: 90000 });
    // the launches arrive after the page loads (Launch Library)
    const loaded = await page.waitForFunction(
        () => typeof dbMissions !== 'undefined' && Array.isArray(dbMissions) && dbMissions.length > 0,
        { timeout: 90000, polling: 1000 }).then(() => true, () => false);
    if (!loaded) {
        report(['## Hero status timeline check', '', 'Could not check: the site loaded no launches this time (Launch Library may have rate-limited this run). Nothing to report.']);
        console.log('No launch data loaded - skipped.');
    } else {
        const checkOnce = () => page.evaluate(async (windowHours, nearDays) => {
            const need = ['candidateSlugsFromMissionName', 'pickHeroTimelineTile', 'namesReferToSameMission',
                'parseCmsPostLaunchTimeline', 'fetchSpacexCms'];
            const missing = need.filter(n => typeof window[n] !== 'function');
            if (missing.length) return { broken: 'The site no longer has: ' + missing.join(', ') };
            const tiles = await fetchSpacexCms(SPACEX_UPCOMING_TILES_URL).then(r => (r.ok ? r.json() : [])).catch(() => []);
            const now = Date.now();
            const launches = [];
            for (const m of dbMissions) {
                const t = new Date(m.date).getTime();
                const hours = (t - now) / 3600000;
                const name = m.patchTagName || m.name;
                const slugs = candidateSlugsFromMissionName(name);
                const tile = pickHeroTimelineTile(tiles, name);
                if (tile && tile.link && !slugs.includes(tile.link)) slugs.unshift(tile.link);
                let found = null;
                const tried = [];
                for (const slug of slugs) {
                    const res = await fetchSpacexCms(SPACEX_MISSION_DETAIL_URL_BASE + encodeURIComponent(slug)).catch(() => null);
                    if (!res || !res.ok) { tried.push(slug + ' (' + (res ? res.status : 'no answer') + ')'); continue; }
                    const mission = await res.json().catch(() => null);
                    const data = mission && parseCmsPostLaunchTimeline(mission, slug);
                    const same = !!mission && namesReferToSameMission((data && data.title) || mission.title, slug, name);
                    tried.push(slug + (data ? ' (timeline: ' + data.postLaunchTimeline.length + ' events' : ' (no timeline') + (same ? ')' : ', name does not match)'));
                    if (data && same) { found = { slug, events: data.postLaunchTimeline.length }; break; }
                }
                launches.push({ name, date: m.date, hours: Math.round(hours * 10) / 10, inWindow: hours <= windowHours && hours > -2, found, tried });
            }
            const unmatchedTiles = (Array.isArray(tiles) ? tiles : [])
                .filter(t => t && t.link && !dbMissions.some(m => namesReferToSameMission(t.title, t.link, m.patchTagName || m.name)))
                .map(t => {
                    const day = t.launchDate ? Date.parse(t.launchDate + 'T12:00:00Z') : NaN;
                    const near = !isNaN(day) && dbMissions.some(m => Math.abs(new Date(m.date).getTime() - day) <= nearDays * 86400000);
                    return { slug: t.link, title: t.title || '', date: t.launchDate || '', near };
                });
            return { launches, unmatchedTiles, tiles: (Array.isArray(tiles) ? tiles : []).map(t => t && t.link) };
        }, WINDOW_HOURS, TILE_NEAR_DAYS);
        const hasProblem = r => !r.broken && (r.launches.some(l => l.inWindow && !l.found) || r.unmatchedTiles.some(t => t.near));
        let result = await checkOnce();
        for (let attempt = 2; attempt <= ATTEMPTS && (hasProblem(result) || (!result.broken && !result.tiles.length)); attempt++) {
            console.log(`Attempt ${attempt - 1}: ${result.tiles.length ? 'a problem' : 'no answer from SpaceX'} - checking again in ${ATTEMPT_WAIT_MS / 1000} s`);
            await new Promise(r => setTimeout(r, ATTEMPT_WAIT_MS));
            result = await checkOnce();
        }
        const noAnswer = !result.broken && !result.tiles.length;

        const lines = ['## Hero status timeline check', '', `Checked ${new Date().toISOString()} against ${SITE}`, ''];
        if (result.broken) {
            lines.push('**The check itself broke:** ' + result.broken);
            exitCode = 1;
        } else if (noAnswer) {
            lines.push(`Could not check: SpaceX's server gave no upcoming launches in ${ATTEMPTS} tries (it didn't answer this runner). Nothing to report.`);
        } else {
            const problems = result.launches.filter(l => l.inWindow && !l.found);
            if (problems.length) {
                lines.push(`### Launches in the next ${WINDOW_HOURS} hours with no status timeline`, '');
                problems.forEach(l => lines.push(`- **${l.name}** - ${l.date} (in ${l.hours} h). Tried: ${l.tried.join(', ') || 'no slug'}`));
                lines.push('');
            }
            const tileProblems = result.unmatchedTiles.filter(t => t.near);
            const tileNotes = result.unmatchedTiles.filter(t => !t.near);
            if (tileProblems.length) {
                lines.push(`### SpaceX pages the site matches to none of its launches, with a launch of the site's within ${TILE_NEAR_DAYS} days of them`, '');
                tileProblems.forEach(t => lines.push(`- \`${t.slug}\` - "${t.title}" ${t.date}`));
                lines.push('');
            }
            if (problems.length || tileProblems.length) exitCode = 1;
            lines.push('### All upcoming launches', '');
            result.launches.forEach(l => lines.push(`- ${l.found ? 'OK' : (l.inWindow ? 'MISSING' : 'not yet')} - ${l.name} - ${l.date} - ${l.found ? l.found.slug + ' (' + l.found.events + ' events)' : 'tried: ' + (l.tried.join(', ') || 'no slug')}`));
            if (tileNotes.length) {
                lines.push('', `SpaceX pages with no launch of the site's near them (no date yet, or Launch Library doesn't have the launch yet) - not a problem: ${tileNotes.map(t => t.slug + (t.date ? ' ' + t.date : '')).join(', ')}`);
            }
            lines.push('', 'SpaceX upcoming tiles: ' + (result.tiles.join(', ') || 'none'));
        }
        report(lines);
        console.log(lines.join('\n'));
    }
} catch (e) {
    report(['## Hero status timeline check', '', '**The check itself failed:** ' + (e && e.message)]);
    console.error(e);
    exitCode = 1;
} finally {
    await browser.close();
}
process.exit(exitCode);
