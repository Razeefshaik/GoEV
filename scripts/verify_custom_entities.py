"""Exercise custom fleet/station forms against the real research API on isolated port 8082."""
import json
import sys
from pathlib import Path
from datetime import datetime, timezone
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'var/verification-python'))
from playwright.sync_api import sync_playwright
OUT = ROOT / 'output/v2g/frontend'
OUT.mkdir(parents=True, exist_ok=True)
errors=[]
with sync_playwright() as p:
    browser=p.chromium.launch(headless=True, executable_path=r'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe')
    page=browser.new_page(viewport={'width':1600,'height':1000})
    page.on('pageerror',lambda e:errors.append(str(e)))
    page.goto('http://localhost:8082/v2g',wait_until='networkidle')
    page.wait_for_function('!busy && scenarios.length === 13')
    page.locator('#start').click()
    page.wait_for_function('!busy && run',timeout=90000)
    initial=page.evaluate('run.id')
    page.locator('#builderToggle').click()
    page.locator('#addStation').click()
    page.locator('#entity-id').fill('DEMO-STATION')
    page.locator('#entity-bus').select_option('10')
    page.locator('#entity-externalOccupiedPorts').fill('3')
    page.locator('#saveEntity').click()
    assert page.locator('#entityError').is_visible()
    page.locator('#entity-externalOccupiedPorts').fill('0')
    page.locator('#saveEntity').click()
    assert not page.locator('#entityDialog').is_visible()
    page.locator('#addEv').click()
    page.locator('#entity-id').fill('DEMO-EV')
    page.locator('#entity-bus').select_option('10')
    page.locator('#saveEntity').click()
    assert page.locator('#draftEvs .draft-row').count()==5
    assert page.locator('#draftStations .draft-row').count()==4
    assert page.evaluate('run.scenario.fleet.length')==4
    assert page.evaluate('run.id')==initial
    page.locator('#addEv').click()
    page.locator('#entity-id').fill('DEMO-EV')
    page.locator('#saveEntity').click()
    assert 'already exists' in page.locator('#entityError').inner_text()
    page.locator('#cancelEntity').click()
    page.get_by_role('button',name='Edit DEMO-EV',exact=True).click()
    page.locator('#entity-soc').fill('80')
    page.locator('#saveEntity').click()
    page.reload(wait_until='networkidle')
    page.wait_for_function('!busy && run')
    page.locator('#builderToggle').click()
    assert page.locator('#draftEvs .draft-row').count()==5
    assert page.evaluate('JSON.parse(el("config").value).fleet.find(e=>e.id==="DEMO-EV").soc')==80
    page.locator('#applyDraft').click()
    page.wait_for_function('!busy && run.scenario.fleet.length===5',timeout=90000)
    assert page.evaluate('run.id')!=initial
    assert page.evaluate('run.scenario.stations.length')==4
    assert 'match the current experiment' in page.locator('#draftStatus').inner_text()
    assert page.locator('.ev-card').count()==5
    page.locator('#hours').select_option('0.25')
    page.locator('#advance').click()
    page.wait_for_function('!busy && run.hour===.25',timeout=90000)
    assert page.locator('#error').is_hidden()
    assert page.evaluate('run.sessions.some(s=>s.evId==="DEMO-EV" && s.actualKw>0)')
    assert page.locator('#topology [data-station="DEMO-STATION"]').count()==1
    page.locator('#customBuilder').scroll_into_view_if_needed()
    page.screenshot(path=str(OUT/'custom-fleet-desktop.png'))
    page.get_by_role('button',name='Remove DEMO-EV',exact=True).click()
    assert page.locator('#draftEvs .draft-row').count()==4
    assert page.evaluate('run.scenario.fleet.length')==5
    page.locator('#resetDraft').click()
    assert page.locator('#draftStations .draft-row').count()==3
    assert page.evaluate('run.scenario.stations.length')==4
    page.set_viewport_size({'width':390,'height':844})
    page.locator('#addEv').click()
    assert page.locator('#entityDialog').is_visible()
    assert page.evaluate('document.documentElement.scrollWidth<=innerWidth+1')
    page.screenshot(path=str(OUT/'custom-ev-mobile.png'))
    page.locator('#cancelEntity').click()
    assert not errors,errors
    browser.close()
report={'timestampUtc':datetime.now(timezone.utc).isoformat(),'passed':True,
        'checks':['add EV and EVCS','duplicate ID and occupied-port validation','edit and browser draft restore',
                  'custom run admission and simulated discharge','remove and restore defaults','current run isolation','mobile editor'],
        'pageErrors':errors}
(OUT/'custom-entities-validation.json').write_text(json.dumps(report,indent=2))
print(json.dumps(report,indent=2))
