"""The clock card's displays (dial, analog, flip, digital) at each size, pushed with Tessera's own code and rendered."""
import asyncio, json, sys
sys.path.insert(0, "/tmp/tessera-ha/screen_manager/app")
sys.path.insert(0, "/home/claude/tilescreen/test")
from aioesphomeapi import APIClient
from core import Grid, state_message
from layout_migrations import migrate_legacy
from page_layout import compile_tiles
from page_delivery import Sender
from render import command

PORT = int(sys.argv[1])

async def main():
    cli = APIClient("127.0.0.1", PORT, None, client_info="Home Assistant test")
    await cli.connect(login=True)
    _, services = await cli.list_entities_services()
    msg = next(s for s in services if s.name == "screen_message")
    async def send(message):
        r = await cli.execute_service(msg, {"message": json.dumps(message, separators=(",", ":"))}, return_response=True)
        return json.loads(r.response_data)
    grid = Grid(4, 3)
    tiles = []
    # Page 1: one of each display as a single card and wide; page 2: tall and square; pages 3-6: each full.
    slot = 0
    for d in ("dial", "analog", "flip", "digital"):
        tiles.append({"entity": "screen.clock", "name": "Clock", "slot": slot, "options": {"display": d}}); slot += 1
    for d in ("dial", "analog"):
        tiles.append({"entity": "screen.clock", "name": "Clock", "slot": slot, "options": {"display": d, "size": "wide"}}); slot += 2
    for d in ("flip", "digital"):
        tiles.append({"entity": "screen.clock", "name": "Clock", "slot": slot, "options": {"display": d, "size": "wide"}}); slot += 2
    slot = 12
    for i, d in enumerate(("dial", "analog", "flip", "digital")):
        tiles.append({"entity": "screen.clock", "name": "Clock", "slot": slot + i, "options": {"display": d, "size": "tall"}})
    slot = 24
    tiles.append({"entity": "screen.clock", "name": "Clock", "slot": 24, "options": {"display": "dial", "size": "square"}})
    tiles.append({"entity": "screen.clock", "name": "Clock", "slot": 26, "options": {"display": "flip", "size": "square"}})
    tiles.append({"entity": "screen.clock", "name": "Clock", "slot": 36, "options": {"display": "analog", "size": "square"}})
    tiles.append({"entity": "screen.clock", "name": "Clock", "slot": 38, "options": {"display": "dial", "size": "1x3"}})
    for i, d in enumerate(("dial", "analog", "flip", "digital")):
        tiles.append({"entity": "screen.clock", "name": "Clock", "slot": 48 + 12 * i, "options": {"display": d, "size": "full"}})
    record = migrate_legacy({"title": "Clocks", "pages": 8, "tiles": tiles}, grid)
    compiled = compile_tiles(record["layout"], grid)
    values = [state_message(i, t, {}) for i, t in enumerate(compiled)]
    region = {"clock_24h": False, "numbers": "english", "group_min": 1, "percent_space": False, "keepalive": 30}
    sender = Sender(send)
    print("sync:", await sender.synchronize("text.kitchen_fire_tile_settings", record, region, values, [[{"k": "clock"}]] * 8), sender.phase)
    await command("render c1")
    for i in range(2, 9):
        await command("tap 988 584", wait=1.2)
        await command(f"render c{i}")
    await cli.disconnect()

asyncio.run(main())
