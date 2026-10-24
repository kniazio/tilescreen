"""A sensor's and a door's history card, answered with the add-on's own history messages (history_card.line, timeline)."""
import asyncio, json, math, sys, time
from zoneinfo import ZoneInfo
sys.path.insert(0, "/tmp/tessera-ha/screen_manager/app")
sys.path.insert(0, "/home/claude/tilescreen/test")
from aioesphomeapi import APIClient
from core import Grid, state_message
from layout_migrations import migrate_legacy
from page_layout import compile_tiles
from page_delivery import Sender
import history_card
from render import command

PORT = int(sys.argv[1])
TZ = ZoneInfo("America/Chicago")
STATES = {
    "sensor.outside": {"state": "54.3", "attributes": {"unit_of_measurement": "°F", "device_class": "temperature", "friendly_name": "Outside"}},
    "binary_sensor.back_door": {"state": "off", "attributes": {"device_class": "door", "friendly_name": "Back door"}},
}

def changes(entity, hours, start, end):
    if entity.startswith("sensor."):
        out, t = [], start - 600
        while t < end:
            out.append((t, 50 + 8 * math.sin((t - start) / (hours * 3600) * 2 * math.pi) + 2 * math.sin(t / 900)))
            t += max(60, hours * 3600 // 200)
        return out
    out, t, state = [(start - 100, "off")], start, "off"
    while t < end:
        t += (hours * 3600) // 14
        state = "on" if state == "off" else "off"
        out.append((t, state))
    return out

async def main():
    cli = APIClient("127.0.0.1", PORT, None, client_info="Home Assistant test")
    await cli.connect(login=True)
    _, services = await cli.list_entities_services()
    msg = next(s for s in services if s.name == "screen_message")
    async def send(message):
        r = await cli.execute_service(msg, {"message": json.dumps(message, separators=(",", ":"))}, return_response=True)
        return json.loads(r.response_data)
    sender = Sender(send)
    async def answer(c):
        if c.service != "esphome.screen_history": return
        d = dict(c.data); e, hours = d["entity"], int(d["hours"])
        start, end = history_card.window(hours)
        ch = changes(e, hours, start, end)
        if e.startswith("sensor."):
            m = history_card.line(e, hours, ch, start, end, TZ, None, "°F")
        else:
            m = history_card.timeline(e, hours, ch, start, end, TZ, STATES[e]["attributes"])
        await sender.auxiliary({**m, "view": int(d["view"])}, session=d["session"], revision=d["rev"])
    cli.subscribe_service_calls(lambda c: asyncio.ensure_future(answer(c)))
    grid = Grid(4, 3)
    record = migrate_legacy({"title": "History", "pages": 1, "tiles": [
        {"entity": "sensor.outside", "name": "Outside", "slot": 0},
        {"entity": "binary_sensor.back_door", "name": "Back door", "slot": 1}]}, grid)
    compiled = compile_tiles(record["layout"], grid)
    values = [state_message(i, t, STATES) for i, t in enumerate(compiled)]
    region = {"clock_24h": False, "numbers": "english", "group_min": 1, "percent_space": False, "keepalive": 30}
    print("sync:", await sender.synchronize("text.kitchen_fire_tile_settings", record, region, values, [[{"k": "clock"}]]), sender.phase)
    for line in sys.argv[2:]:
        await command(line, wait=1.0)
    await cli.disconnect()

asyncio.run(main())
