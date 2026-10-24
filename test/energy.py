"""The energy card with the add-on's own payload (energy_flow.payload): a house like Ken's, and one with everything."""
import asyncio, json, sys
sys.path.insert(0, "/tmp/tessera-ha/screen_manager/app")
sys.path.insert(0, "/home/claude/tilescreen/test")
from aioesphomeapi import APIClient
from core import Grid, state_message
from layout_migrations import migrate_legacy
from page_layout import compile_tiles
from page_delivery import Sender
import energy_flow
from render import command

PORT = int(sys.argv[1])
W = lambda v, u="W": {"state": str(v), "attributes": {"unit_of_measurement": u, "device_class": "power"}}
KEN = {"energy_sources": [{"type": "grid", "stat_rate": "sensor.house_mains_power"}], "device_consumption": []}
KEN_STATES = {"sensor.house_mains_power": W(1740)}
FULL = {"energy_sources": [{"type": "grid", "stat_rate": "sensor.grid"}, {"type": "solar", "stat_rate": "sensor.solar"},
                           {"type": "battery", "stat_rate": "sensor.battery", "stat_soc": "sensor.soc"}],
        "device_consumption": [{"stat_consumption": "sensor.e1", "stat_rate": "sensor.car", "name": "Car"},
                               {"stat_consumption": "sensor.e2", "stat_rate": "sensor.heat_pump", "name": "Heat pump"},
                               {"stat_consumption": "sensor.e3", "stat_rate": "sensor.fridge", "name": "Fridge"},
                               {"stat_consumption": "sensor.e4", "stat_rate": "sensor.tv", "name": "TV"},
                               {"stat_consumption": "sensor.e5", "stat_rate": "sensor.dryer", "name": "Dryer"}]}
FULL_STATES = {"sensor.grid": W(-0.8, "kW"), "sensor.solar": W(4.2, "kW"), "sensor.battery": W(-900), "sensor.soc": {"state": "64", "attributes": {}},
               "sensor.car": W(1100), "sensor.heat_pump": W(650), "sensor.fridge": W(90), "sensor.tv": W(120), "sensor.dryer": W(300)}

async def main():
    cli = APIClient("127.0.0.1", PORT, None, client_info="Home Assistant test")
    await cli.connect(login=True)
    _, services = await cli.list_entities_services()
    msg = next(s for s in services if s.name == "screen_message")
    async def send(message):
        r = await cli.execute_service(msg, {"message": json.dumps(message, separators=(",", ":"))}, return_response=True)
        return json.loads(r.response_data)
    sender = Sender(send)
    grid = Grid(4, 3)
    record = migrate_legacy({"title": "Energy", "pages": 1, "tiles": [
        {"entity": "screen.energy", "name": "Energy", "slot": 0, "options": {"size": "square"}},
        {"entity": "screen.energy", "name": "Energy", "slot": 2, "options": {"size": "square", "flow": "lines"}}]}, grid)
    compiled = compile_tiles(record["layout"], grid)
    values = []
    for i, t in enumerate(compiled):
        v = state_message(i, t, {})
        v["x"] = energy_flow.payload(KEN, KEN_STATES, None, "Home") if i == 0 else energy_flow.payload(FULL, FULL_STATES, None, "Home")
        values.append(v)
    print("x:", values[0]["x"], "\n   ", values[1]["x"])
    region = {"clock_24h": False, "numbers": "english", "group_min": 1, "percent_space": False, "keepalive": 30}
    print("sync:", await sender.synchronize("text.kitchen_fire_tile_settings", record, region, values, [[{"k": "clock"}]]), sender.phase)
    for line in sys.argv[2:]:
        await command(line, wait=1.0)
    await cli.disconnect()

asyncio.run(main())
