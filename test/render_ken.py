"""Pushes a layout with Tessera's own code, then renders frames and replays touches."""
import asyncio, json, sys, time
from pathlib import Path
sys.path.insert(0, "/tmp/tessera-ha/screen_manager/app")
from aioesphomeapi import APIClient
from core import Grid, state_message
from layout_migrations import migrate_legacy
from page_layout import compile_tiles
from page_delivery import Sender

PORT, COLS, ROWS = int(sys.argv[1]), int(sys.argv[2]), int(sys.argv[3])
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
    record = migrate_legacy({"title": "Home", "pages": 2, "tiles": [
        {"entity": "light.fr", "name": "Living Room Front", "slot": 0},
        {"entity": "light.lamp", "name": "Living Room Lamp", "slot": 1},
        {"entity": "media_player.tv", "name": "Living Room TV", "slot": 2, "options": {"size": "square", "controls": "volume_playback"}},
        {"entity": "light.back", "name": "Living Room Back", "slot": 4},
        {"entity": "light.sc", "name": "Living Room Sconce", "slot": 8},
        {"entity": "climate.lr", "name": "Living Room", "slot": 10, "options": {"size": "wide", "controls": "setpoint_mode"}},
        {"entity": "light.fr", "name": "Living Room Front", "slot": 12},
        {"entity": "media_player.tv", "name": "Living Room TV", "slot": 14, "options": {"size": "wide", "controls": "volume"}},
        {"entity": "climate.lr", "name": "Living Room", "slot": 18, "options": {"size": "square", "controls": "setpoint_mode"}},
        {"entity": "light.back", "name": "Living Room Back", "slot": 16},
    ]}, grid)
    tv = {"state": "on", "attributes": {"volume_level": 0.42, "is_volume_muted": False, "supported_features": 0x1FFFFF,
          "source": "HDMI 1", "source_list": ["HDMI 1", "HDMI 2", "Roku", "Antenna TV"], "device_class": "tv"}}
    ha = {
        "light.fr": {"state": "on", "attributes": {"brightness": 74, "supported_color_modes": ["brightness"]}},
        "light.lamp": {"state": "on", "attributes": {"brightness": 54, "supported_color_modes": ["brightness"]}},
        "light.back": {"state": "off", "attributes": {"supported_color_modes": ["brightness"]}},
        "light.sc": {"state": "on", "attributes": {"brightness": 179, "supported_color_modes": ["brightness"]}},
        "media_player.tv": tv,
        "climate.lr": {"state": "off", "attributes": {"current_temperature": 73, "temperature": 72.5, "hvac_modes": ["off", "heat", "cool", "heat_cool"],
                        "min_temp": 50, "max_temp": 90, "target_temp_step": 0.5, "temperature_unit": "°F", "supported_features": 1|2|128|256}},
    }
    tiles = compile_tiles(record["layout"], grid)
    values = [state_message(i, t, ha) for i, t in enumerate(tiles)]
    import media_library
    for v in values:
        if v["entity"] == "media_player.tv": v["x"] = media_library.player_extras(tv["attributes"])
    for v in values: print("  controls", v["entity"], (v.get("o") or {}).get("controls"), (v.get("o") or {}).get("size"))
    bars = [[{"k": "text", "i": "F050F", "t": "54°"}, {"k": "date"}, {"k": "clock"}], [{"k": "clock"}]]
    region = {"clock_24h": False, "numbers": "english", "group_min": 1, "percent_space": False, "keepalive": 30}
    sender = Sender(send)
    print("sync:", await sender.synchronize("text.kitchen_fire_tile_settings", record, region, values, bars), sender.phase)
    await command("render 01-page1")

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

asyncio.run(run())
