"""Pushes a layout with Tessera's own code, then renders frames and replays touches."""
import asyncio, json, sys, time
from pathlib import Path
sys.path.insert(0, "/tmp/tessera-ha/screen_manager/app")
from aioesphomeapi import APIClient
from core import Grid, state_message
from layout_migrations import migrate_legacy
from page_layout import compile_tiles
from page_delivery import Sender

try:
    PORT, COLS, ROWS = int(sys.argv[1]), int(sys.argv[2]), int(sys.argv[3])
except (IndexError, ValueError):  # imported by another harness with its own arguments
    PORT, COLS, ROWS = int(sys.argv[1]), 4, 3
CMD = Path("/tmp/ts-cmd")

async def command(line, wait=0.6):
    while CMD.exists(): await asyncio.sleep(0.05)
    CMD.write_text(line)
    await asyncio.sleep(wait)

async def main():
    cli = APIClient("127.0.0.1", PORT, None, client_info="Home Assistant test")
    await cli.connect(login=True)
    entities, services = await cli.list_entities_services()
    calls = []
    cli.subscribe_service_calls(lambda c: calls.append(c))
    msg = next(s for s in services if s.name == "screen_message")
    async def send(message):
        r = await cli.execute_service(msg, {"message": json.dumps(message, separators=(",", ":"))}, return_response=True)
        return json.loads(r.response_data)
    grid = Grid(COLS, ROWS)
    record = migrate_legacy({"title": "Kitchen", "pages": 2, "tiles": [
        {"entity": "screen.clock", "name": "Clock", "slot": 0, "options": {"size": "wide"}},
        {"entity": "weather.home", "name": "Weather", "slot": 2},
        {"entity": "sensor.kitchen_temp", "name": "Kitchen", "slot": 3},
        {"entity": "light.island", "name": "Island lights", "slot": 4, "options": {"size": "wide"}},
        {"entity": "climate.house", "name": "Thermostat", "slot": 6, "options": {"size": "wide"}},
        {"entity": "switch.coffee", "name": "Coffee maker", "slot": 8},
        {"entity": "cover.garage", "name": "Garage door", "slot": 9},
        {"entity": "scene.dinner", "name": "Dinner", "slot": 10, "options": {"background": "orange"}},
        {"entity": "screen.page_2", "name": "More", "slot": 11},
        {"entity": "media_player.kitchen", "name": "Kitchen speaker", "slot": 12, "options": {"size": "wide"}},
        {"entity": "lock.front", "name": "Front door", "slot": 14},
        {"entity": "binary_sensor.back_door", "name": "Back door", "slot": 15},
        {"entity": "person.ken", "name": "Ken", "slot": 16},
        {"entity": "light.porch", "name": "Porch", "slot": 17},
    ]}, grid)
    ha = {
        "weather.home": {"state": "partlycloudy", "attributes": {"temperature": 54, "temperature_unit": "°F", "humidity": 61}},
        "sensor.kitchen_temp": {"state": "71.4", "attributes": {"unit_of_measurement": "°F", "device_class": "temperature"}},
        "light.island": {"state": "on", "attributes": {"brightness": 191, "supported_color_modes": ["brightness"]}},
        "climate.house": {"state": "heat", "attributes": {"current_temperature": 69, "temperature": 70, "hvac_action": "heating", "hvac_modes": ["off", "heat", "cool", "heat_cool"], "min_temp": 50, "max_temp": 90, "target_temp_step": 1, "temperature_unit": "°F"}},
        "switch.coffee": {"state": "off", "attributes": {}},
        "cover.garage": {"state": "closed", "attributes": {"current_position": 0, "device_class": "garage"}},
        "scene.dinner": {"state": "2026-10-20T18:00:00", "attributes": {}},
        "media_player.kitchen": {"state": "playing", "attributes": {"media_title": "Hejira", "volume_level": 0.35}},
        "lock.front": {"state": "locked", "attributes": {}},
        "binary_sensor.back_door": {"state": "on", "attributes": {"device_class": "door"}},
        "person.ken": {"state": "home", "attributes": {}},
        "light.porch": {"state": "unavailable", "attributes": {}},
    }
    tiles = compile_tiles(record["layout"], grid)
    values = [state_message(i, t, ha) for i, t in enumerate(tiles)]
    bars = [[{"k": "text", "i": "F050F", "t": "54°"}, {"k": "date"}, {"k": "clock"}], [{"k": "clock"}]]
    region = {"clock_24h": False, "numbers": "english", "group_min": 1, "percent_space": False, "keepalive": 30}
    sender = Sender(send)
    print("sync:", await sender.synchronize("text.kitchen_fire_tile_settings", record, region, values, bars), sender.phase)
    await command("render 01-page1")
    await command("hold 640 300")            # the thermostat's card
    await command("render 02-card-climate")
    await command("tap 20 20")               # outside the card: closes it
    W = 1024
    return cli, calls, command, sender, record, region, values, bars

async def run():
    cli, calls, command, sender, record, region, values, bars = await main()
    # Taps: coffee toggles, dinner runs, garage opens its card, thermostat +, slider drag on the island light.
    shots = json.loads(Path("/tmp/ts-taps.json").read_text()) if Path("/tmp/ts-taps.json").exists() else []
    entities, services = await cli.list_entities_services()
    for line in shots:
        if line.startswith("HA:set "):
            _, name, val = line.split(" ", 2)
            name = name.replace("_", " ")
            ent = next(e for e in entities if e.name == name)
            if type(ent).__name__ == "SwitchInfo": cli.switch_command(ent.key, val == "1")
            else: cli.number_command(ent.key, float(val))
            await asyncio.sleep(0.4)
            continue
        if line == "HA:alert":
            a = next(s for s in services if s.name == "show_alert_choice")
            await cli.execute_service(a, {"title": "Someone is at the door", "subtitle": "Front door camera", "icon": "doorbell", "color": "orange",
                                          "button_text": "Coming", "timeout": 0, "flash": False, "button_color": "", "button2_text": "Ignore", "button2_color": ""})
            await asyncio.sleep(0.4)
            continue
        await command(line)
    await asyncio.sleep(0.5)
    for c in calls: print("  HA call:", c.service, dict(c.data))
    await cli.disconnect()

if __name__ == "__main__":
    asyncio.run(run())
