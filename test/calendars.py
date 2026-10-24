"""Calendar cards at every size and the full screen, with the add-on's own packing (calendar_feed.card)."""
import asyncio, json, sys
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo
sys.path.insert(0, "/tmp/tessera-ha/screen_manager/app")
sys.path.insert(0, "/home/claude/tilescreen/test")
from aioesphomeapi import APIClient
from core import Grid, state_message
from layout_migrations import migrate_legacy
from page_layout import compile_tiles
from page_delivery import Sender
import calendar_feed
from render import command

PORT = int(sys.argv[1])
TZ = ZoneInfo("America/Chicago")
NOW = datetime.now(TZ)
def at(day, h, m=0): return (NOW.replace(hour=0, minute=0, second=0, microsecond=0) + timedelta(days=day, hours=h, minutes=m)).isoformat()
def ev(day, h, dur, title, place="", desc=""):
    return {"start": at(day, h), "end": (datetime.fromisoformat(at(day, h)) + timedelta(minutes=dur)).isoformat(), "summary": title, "location": place, "description": desc}
h = NOW.hour
FAMILY = [ev(0, max(0, h - 1), 120, "Piano lesson", "Music school", "Bring the new book"), ev(0, min(22, h + 2), 60, "Dinner with the Hansens", "Home"),
          ev(1, 9, 30, "Dentist", "Dr. Ruiz"), ev(1, 18, 90, "Soccer practice", "Field 3"), ev(2, 12, 60, "Lunch with Sam"),
          {"start": (NOW.date() + timedelta(days=3)).isoformat(), "end": (NOW.date() + timedelta(days=5)).isoformat(), "summary": "Grandma visiting"},
          ev(4, 10, 45, "Car service"), ev(6, 15, 120, "Birthday party", "Pizza place")]
WORK = [ev(0, max(0, h - 3), 60, "Standup"), ev(1, 13, 60, "Budget review", "Room 204"), ev(3, 9, 180, "Training")]
ROOM = [ev(0, min(23, h + 1), 45, "Weekly sync"), ev(0, min(23, h + 3), 60, "Vendor call")]
STATES = {"calendar.family": {"state": "on", "attributes": {"friendly_name": "Family"}},
          "calendar.work": {"state": "off", "attributes": {"friendly_name": "Work"}},
          "calendar.room": {"state": "off", "attributes": {"friendly_name": "Room 204", "supported_features": 7}}}

async def main():
    cli = APIClient("127.0.0.1", PORT, None, client_info="Home Assistant test")
    await cli.connect(login=True)
    _, services = await cli.list_entities_services()
    msg = next(s for s in services if s.name == "screen_message")
    calls = []
    cli.subscribe_service_calls(lambda c: calls.append((c.service, dict(c.data))))
    async def send(message):
        r = await cli.execute_service(msg, {"message": json.dumps(message, separators=(",", ":"))}, return_response=True)
        return json.loads(r.response_data)
    sender = Sender(send)
    grid = Grid(4, 3)
    both = {"calendars": ["calendar.work"]}
    tiles = [
        {"entity": "calendar.family", "name": "Family", "slot": 0, "options": {**both}},
        {"entity": "calendar.family", "name": "Family", "slot": 1, "options": {**both, "style": "busy_free"}},
        {"entity": "calendar.family", "name": "Family", "slot": 2, "options": {**both, "size": "square"}},
        {"entity": "calendar.family", "name": "Family", "slot": 4, "options": {**both, "size": "1x2" if False else "tall"}},
        {"entity": "calendar.room", "name": "Room 204", "slot": 12, "options": {"style": "room", "size": "square", "book_place": "Room 204"}},
        {"entity": "calendar.family", "name": "Family", "slot": 20, "options": {**both, "size": "4x1"}},
        {"entity": "calendar.family", "name": "Family", "slot": 24, "options": {**both, "size": "4x2"}},
        {"entity": "calendar.family", "name": "Family", "slot": 36, "options": {**both, "size": "full"}},
    ]
    record = migrate_legacy({"title": "Calendars", "pages": 4, "tiles": tiles}, grid)
    compiled = compile_tiles(record["layout"], grid)
    fetched = {"calendar.family": FAMILY, "calendar.work": WORK, "calendar.room": ROOM}
    values = []
    for i, t in enumerate(compiled):
        v = state_message(i, t, STATES)
        v["x"] = {"cal": calendar_feed.card(t, fetched, STATES, {}, TZ, NOW)}
        values.append(v)
    print("first:", json.dumps(values[0]["x"])[:300])
    region = {"clock_24h": False, "numbers": "english", "group_min": 1, "percent_space": False, "keepalive": 30}
    print("sync:", await sender.synchronize("text.kitchen_fire_tile_settings", record, region, values, [[{"k": "clock"}]] * 4), sender.phase)
    for line in sys.argv[2:]:
        await command(line, wait=1.0)
    await asyncio.sleep(0.5)
    for c in calls: print("  HA call:", c)
    await cli.disconnect()

asyncio.run(main())
