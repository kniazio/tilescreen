"""A colour light's card and its effects page, with the add-on's own state messages, effect rows and option pages."""
import asyncio, json, sys
sys.path.insert(0, "/tmp/tessera-ha/screen_manager/app")
sys.path.insert(0, "/home/claude/tilescreen/test")
from aioesphomeapi import APIClient
from core import Grid, state_message
from layout_migrations import migrate_legacy
from page_layout import compile_tiles
from page_delivery import Sender
import light_effects
from render import command

PORT = int(sys.argv[1])
KEN = "--ken" in sys.argv
if KEN: sys.argv.remove("--ken")
EFFECTS = ["Solid", "Blink", "Breathe", "Chase", "Colorloop", "Fireworks", "Rainbow", "Ripple", "Scanner", "Sparkle",
           "Strobe", "Theater", "Twinkle", "Wipe", "Aurora", "Candle", "Comet", "Fire 2012", "Lake", "Meteor", "Noise", "Pacifica"]
STATES = {
    "light.desk": {"state": "on", "attributes": {"brightness": 180, "supported_color_modes": ["hs", "color_temp"], "color_mode": "hs",
                   "hs_color": [210, 80], "color_temp_kelvin": None, "min_color_temp_kelvin": 2000, "max_color_temp_kelvin": 6535,
                   "supported_features": 44, "effect": "Rainbow", "effect_list": EFFECTS, "friendly_name": "Desk strip"}},
    "select.desk_color_palette": {"state": "Ocean", "attributes": {"options": ["Default", "Ocean", "Forest", "Lava", "Party"]}},
    "select.desk_preset": {"state": "Evening", "attributes": {"options": ["Morning", "Evening", "Movie"]}},
    "number.desk_speed": {"state": "128", "attributes": {"min": 0, "max": 255, "step": 1}},
    "number.desk_intensity": {"state": "64", "attributes": {"min": 0, "max": 255, "step": 1}},
}
DEVICE = [{"entity_id": e, "translation_key": k, "original_name": n} for e, k, n in (("light.desk", None, "Desk"), ("select.desk_color_palette", "color_palette", "Color palette"),
          ("select.desk_preset", "preset", "Preset"), ("number.desk_speed", "speed", "Speed"), ("number.desk_intensity", "intensity", "Intensity"))]

async def main():
    cli = APIClient("127.0.0.1", PORT, None, client_info="Home Assistant test")
    await cli.connect(login=True)
    _, services = await cli.list_entities_services()
    msg = next(s for s in services if s.name == "screen_message")
    calls = []
    async def send(message):
        r = await cli.execute_service(msg, {"message": json.dumps(message, separators=(",", ":"))}, return_response=True)
        return json.loads(r.response_data)
    sender = Sender(send)
    async def answer(c):
        # The add-on's answer to a picker's question, through its own pages and its own sender.
        if c.service == "esphome.screen_options":
            d = dict(c.data)
            names = light_effects.options_for(d["entity"], STATES, ["light.desk"], lambda light: DEVICE)
            pages = light_effects.pages(names)
            await sender.auxiliary({**light_effects.message(d["entity"], int(d["page"]), pages), "view": int(d["view"])},
                                   session=d["session"], revision=d["rev"])
        else:
            calls.append((c.service, dict(c.data), dict(c.data_template)))
    cli.subscribe_service_calls(lambda c: asyncio.ensure_future(answer(c)))
    grid = Grid(4, 3)
    if KEN:
        STATES["light.desk"] = {"state": "on", "attributes": {"effect_list": EFFECTS, "supported_color_modes": ["rgb"], "effect": "Running",
            "color_mode": "rgb", "brightness": 255, "hs_color": [29.077, 50.98], "friendly_name": "WLED-1", "supported_features": 36}}
        for e in list(STATES):
            if not e.startswith("light."): del STATES[e]
        DEVICE[:] = DEVICE[:1]
    record = migrate_legacy({"title": "Lights", "pages": 1, "tiles": [
        {"entity": "light.desk", "name": "Desk strip", "slot": 0, "options": {"size": "wide"}}]}, grid)
    compiled = compile_tiles(record["layout"], grid)
    extra = light_effects.rows("light.desk", DEVICE, STATES)
    print("extra:", extra)
    values = [state_message(0, compiled[0], STATES, extra=extra)]
    region = {"clock_24h": False, "numbers": "english", "group_min": 1, "percent_space": False, "keepalive": 30}
    print("sync:", await sender.synchronize("text.kitchen_fire_tile_settings", record, region, values, [[{"k": "clock"}]]), sender.phase)
    for line in sys.argv[2:]:
        await command(line, wait=1.0)
    await asyncio.sleep(0.5)
    for c in calls: print("  HA call:", c)
    await cli.disconnect()

asyncio.run(main())
