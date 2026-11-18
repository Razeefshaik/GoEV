"""Real browser interaction checks for the presentation dashboard; isolated research app on 8082."""
import json
import sys
from pathlib import Path
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'var/verification-python'))
from playwright.sync_api import sync_playwright

OUT = ROOT / 'output/v2g/frontend'
OUT.mkdir(parents=True, exist_ok=True)
checks, errors = {}, []
with sync_playwright() as p:
    browser = p.chromium.launch(headless=True, executable_path=r'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe',
                                args=['--disable-gpu', '--disable-extensions'])
    page = browser.new_page(viewport={'width': 1600, 'height': 1000})
    page.on('pageerror', lambda e: errors.append(str(e)))
    page.goto('http://localhost:8082/v2g', wait_until='networkidle')
    page.wait_for_function('!busy && scenarios.length === 13')
    page.locator('#start').click()
    page.wait_for_function('!busy && run && run.sessions.length > 0', timeout=90000)
    assert page.locator('.ev-card').count() == 4
    assert page.locator('#topology [data-bus]').count() == 28
    page.locator('#hours').select_option('0.25')
    page.locator('#advance').click()
    page.wait_for_function('!busy && run.hour === .25', timeout=90000)
    assert page.locator('#error').is_hidden()
    model = page.evaluate('({served:run.currentV2g.servedKw,metrics,policy:run.policy,ev:run.sessions})')
    assert f"{model['served']:,.1f}" in page.locator('#cards').inner_text()
    assert len(page.locator('#voltage circle').all()) == 56
    page.locator('#topology [data-bus="14"]').click()
    assert page.locator('#inspector h3').inner_text() == 'Bus 14'
    page.locator('#fleetCards .ev-card').first.click()
    assert page.locator('#inspector h3').inner_text() == 'EV-1'
    page.evaluate("document.querySelector('#voltage').scrollIntoView({block:'center',behavior:'instant'})")
    page.wait_for_timeout(300)
    page.locator('#voltage circle').last.hover()
    assert page.locator('#chartTooltip').is_visible()
    checks['apiValuesAndInteractiveNetwork'] = True
    page.locator('#overview').scroll_into_view_if_needed()
    page.wait_for_timeout(1400)
    page.screenshot(path=str(OUT / 'overview-desktop.png'))
    page.locator('#network').scroll_into_view_if_needed()
    page.screenshot(path=str(OUT / 'network-desktop.png'))
    page.locator('#mapTab').click()
    page.wait_for_function('map && mapVehicles.length === run.sessions.length', timeout=30000)
    assert page.locator('.map-bus').count() == 28
    assert page.locator('.leaflet-control-attribution').is_visible()
    page.locator('.map-bus').first.click()
    assert page.locator('#inspector h3').inner_text().startswith('Bus ')
    page.wait_for_timeout(1800)
    page.screenshot(path=str(OUT / 'map-desktop.png'))
    checks['mapOverlaysAndSelection'] = True
    page.locator('#schematicTab').click()
    page.locator('#play').click()
    page.wait_for_function('run.hour > .25 && !busy', timeout=90000)
    page.locator('#play').click()
    page.wait_for_function('!busy && !playing', timeout=90000)
    paused = page.evaluate('run.hour')
    page.wait_for_timeout(1700)
    assert page.evaluate('run.hour') == paused
    checks['playPause'] = True
    page.locator('#compare').click()
    page.wait_for_function('!busy && window.policyResults?.length === 3', timeout=120000)
    assert page.locator('#policyTable tr').count() == 4
    assert page.evaluate('run.hour') == paused
    comparison = page.evaluate('window.policyResults')
    assert len({round(r['metric']['requestedKwh'], 8) for r in comparison}) == 1
    page.evaluate("document.querySelector('#comparison').scrollIntoView({block:'start',behavior:'instant'})")
    page.screenshot(path=str(OUT / 'comparison-desktop.png'))
    checks['equivalentThreePolicyComparison'] = True
    page.locator('#fleetCards .ev-card').first.click()
    page.locator('#inspectCancel').click()
    page.wait_for_function("!busy && run.sessions.find(s=>s.evId==='EV-1').status==='CANCELLED'", timeout=90000)
    assert page.evaluate("run.sessions.find(s=>s.evId==='EV-1').actualKw") == 0
    checks['cancelUpdatesAuthoritativeState'] = True
    with page.expect_download() as download:
        page.locator('#export').click()
    path = download.value.path()
    exported = json.loads(Path(path).read_text())
    assert exported['run']['id'] == page.evaluate('run.id')
    assert len(exported['policyComparisons']) == 3
    checks['export'] = True
    page.locator('#presentation').click()
    assert page.locator('body').get_attribute('class') == 'presentation'
    page.locator('#presentation').click()
    page.set_viewport_size({'width': 390, 'height': 844})
    page.locator('#overview').scroll_into_view_if_needed()
    assert page.evaluate('document.documentElement.scrollWidth <= innerWidth + 1')
    page.screenshot(path=str(OUT / 'dashboard-mobile.png'), full_page=True)
    page.locator('#mapTab').click()
    page.wait_for_timeout(300)
    assert page.evaluate('document.documentElement.scrollWidth <= innerWidth + 1')
    checks['responsiveAndPresentation'] = True
    page.route('**/tile.openstreetmap.org/**', lambda route: route.abort())
    page.evaluate('map.setZoom(map.getZoom() + 1)')
    page.wait_for_function("document.querySelector('#mapStatus').textContent.includes('background unavailable')", timeout=30000)
    assert page.locator('.map-bus').count() == 28
    checks['offlineMapOverlayFallback'] = True
    page.emulate_media(reduced_motion='reduce')
    page.reload(wait_until='networkidle')
    page.wait_for_function('!busy && run !== null')
    assert page.locator('body').get_attribute('class') == 'no-motion'
    assert page.locator('#motion').get_attribute('aria-pressed') == 'false'
    checks['restartRestoreAndReducedMotion'] = True
    # A no-shortage case must show a true zero-benefit comparison, without NaN graphs.
    page.locator('#scenario').select_option('no-shortage')
    page.locator('#start').click()
    page.wait_for_function("!busy && run.scenario.id === 'no-shortage'", timeout=90000)
    assert page.locator('.ev-card').count() == 4
    assert page.evaluate('run.sessions.length') == 0
    page.locator('#advance').click()
    page.wait_for_function('!busy && run.hour > 0', timeout=90000)
    assert page.evaluate('metrics.withV2g.unservedKwh') < 1e-8
    assert 'NaN' not in page.locator('main').inner_text()
    assert page.locator('#error').is_hidden()
    checks['zeroSupportCase'] = True
    assert not errors, errors
    browser.close()
report = {'timestampUtc': datetime.now(timezone.utc).isoformat(), 'checks': checks,
          'pageErrors': errors, 'passed': all(checks.values()), 'physicalHardware': 'not tested'}
(OUT / 'validation.json').write_text(json.dumps(report, indent=2))
print(json.dumps(report, indent=2))
