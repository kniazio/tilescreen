"""Drives the screen the way Home Assistant and the Tessera add-on do."""
import asyncio, json, sys
from pathlib import Path
sys.path.insert(0, "/tmp/tessera-ha/screen_manager/app")
from aioesphomeapi import APIClient
from core import Grid, state_message
from layout_migrations import migrate_legacy
from page_layout import compile_tiles
from page_delivery import Sender

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 16053
PSK = sys.argv[2] if len(sys.argv) > 2 else None

async def main():
    cli = APIClient("127.0.0.1", PORT, None, client_info="Home Assistant test", noise_psk=PSK)
    await cli.connect(login=True)
    info = await cli.device_info()
    print("device:", info.name, info.friendly_name, info.mac_address, info.project_name, info.project_version, info.esphome_version)
    entities, services = await cli.list_entities_services()
    for e in entities: print("  entity", type(e).__name__, e.object_id, repr(e.name))
    for s in services: print("  action", s.name, [ (a.name, a.type) for a in s.args], s.supports_response)
    states = {}
    cli.subscribe_states(lambda st: states.__setitem__(st.key, st))
    calls = []
    cli.subscribe_service_calls(lambda c: calls.append(c))
    await asyncio.sleep(0.3)
    names = {e.key: e.name for e in entities}
    for k, st in states.items(): print("  state", names.get(k), "=", getattr(st, "state", None))

    msg = next(s for s in services if s.name == "screen_message")
    async def send(message):
        r = await cli.execute_service(msg, {"message": json.dumps(message, separators=(",", ":"))}, return_response=True)
        assert r.success, r.error_message
        return json.loads(r.response_data)

    grid = Grid(4, 3)
    record = migrate_legacy({"title": "Kitchen", "pages": 2, "tiles": [
        {"entity": "light.desk", "name": "Desk lamp", "slot": 0, "options": {"size": "wide"}},
        {"entity": "sensor.temp", "name": "Temperature", "slot": 2},
        {"entity": "climate.house", "name": "House", "slot": 3, "options": {"size": "tall"}},
        {"entity": "switch.fan", "name": "Fan", "slot": 4},
        {"entity": "screen.page_2", "name": "More", "slot": 8},
        {"entity": "cover.garage", "name": "Garage", "slot": 12},
    ]}, grid)
    ha = {
        "light.desk": {"state": "on", "attributes": {"brightness": 180, "friendly_name": "Desk", "supported_color_modes": ["brightness"]}},
        "sensor.temp": {"state": "21.456", "attributes": {"unit_of_measurement": "°C", "device_class": "temperature"}},
        "climate.house": {"state": "heat", "attributes": {"current_temperature": 20.5, "temperature": 21, "hvac_modes": ["off", "heat"], "min_temp": 7, "max_temp": 35}},
        "switch.fan": {"state": "off", "attributes": {}},
        "cover.garage": {"state": "closed", "attributes": {"current_position": 0}},
    }
    tiles = compile_tiles(record["layout"], grid)
    values = [state_message(i, t, ha) for i, t in enumerate(tiles)]
    bars = [[{"k": "clock"}, {"k": "text", "t": "21 °C"}], [{"k": "date"}]]
    region = {"clock_24h": False, "numbers": "english", "group_min": 1, "percent_space": False, "keepalive": 30}
    sender = Sender(send)
    result = await sender.synchronize("text.fire_test_tile_settings", record, region, values, bars)
    print("synchronize ->", result, "phase", sender.phase, "features", sorted(sender.features), "sizes", sorted(sender.tile_sizes))
    # A state change, as the add-on sends one when HA's state changes.
    ha["switch.fan"]["state"] = "on"
    values = [state_message(i, t, ha) for i, t in enumerate(tiles)]
    result = await sender.synchronize("text.fire_test_tile_settings", record, region, values, bars)
    print("state update ->", result)
    print("ping ->", await sender.ping())
    await asyncio.sleep(0.2)
    for k, st in states.items():
        if names.get(k) == "Tile settings": print("  inbox now:", st.state)

    # Actions from HA.
    alert = next(s for s in services if s.name == "show_alert")
    await cli.execute_service(alert, {"title": "Doorbell", "subtitle": "Front door", "icon": "doorbell", "color": "orange", "button_text": "", "timeout": 10, "flash": False})
    page = next(s for s in services if s.name == "show_page")
    await cli.execute_service(page, {"page": 2})
    sleep = next(e for e in entities if e.object_id == "sleep")
    wake = next(e for e in entities if e.object_id == "wake")
    cli.button_command(sleep.key)
    await asyncio.sleep(0.3)
    cli.button_command(wake.key)
    # A tap on the screen: it should reach us as a Home Assistant action.
    Path("/tmp/tilescreen-tap").touch()
    await asyncio.sleep(1.0)
    for c in calls: print("  HA call:", c.service, dict(c.data), "event" if c.is_event else "")
    await cli.disconnect()

asyncio.run(main())
