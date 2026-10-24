"""Camera, cover and screensaver pictures, with the add-on's own request validators and sender."""
import asyncio, http.server, json, os, sys, threading
from pathlib import Path
sys.path.insert(0, "/tmp/tessera-ha/screen_manager/app")
from aioesphomeapi import APIClient
from PIL import Image, ImageDraw, ImageFont
from core import Grid, state_message, media_extras
from layout_migrations import migrate_legacy
from page_layout import compile_tiles
from page_delivery import Sender
import camera_feed

PORT, W, H = int(sys.argv[1]), 1024, 600
CMD, PICS = Path("/tmp/ts-cmd"), Path("/tmp/ts-pics")
PICS.mkdir(exist_ok=True)
class Quiet(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *a, **k): super().__init__(*a, directory=str(PICS), **k)
    def log_message(self, *a): pass
server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Quiet)
threading.Thread(target=server.serve_forever, daemon=True).start()
BASE = f"http://127.0.0.1:{server.server_address[1]}"
FONT = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 34)

def scene(w, h, hue, label):
    im = Image.new("RGB", (w, h))
    d = ImageDraw.Draw(im)
    for y in range(h):
        t = y / max(1, h - 1)
        d.line([(0, y), (w, y)], fill=(int(40 + 60 * t + hue), int(70 + 80 * (1 - t)), int(110 + 90 * t)))
    d.ellipse([w * 0.6, h * 0.15, w * 0.6 + h * 0.3, h * 0.45], fill=(250, 220, 120))
    d.rectangle([0, h * 0.72, w, h], fill=(40, 60, 40))
    d.text((20, 20), label, font=FONT, fill=(255, 255, 255))
    return im

async def command(line, wait=0.6):
    while CMD.exists(): await asyncio.sleep(0.05)
    CMD.write_text(line)
    await asyncio.sleep(wait)

async def main():
    cli = APIClient("127.0.0.1", PORT, None, client_info="Home Assistant test")
    await cli.connect(login=True)
    entities, services = await cli.list_entities_services()
    msg = next(s for s in services if s.name == "screen_message")
    async def send(message):
        r = await cli.execute_service(msg, {"message": json.dumps(message, separators=(",", ":"))}, return_response=True)
        return json.loads(r.response_data)
    sender = Sender(send)
    answers = []
    def on_call(c):
        if c.service == "esphome.screen_camera" and c.is_event:
            answers.append(dict(c.data))
    cli.subscribe_service_calls(on_call)

    grid = Grid(4, 3)
    record = migrate_legacy({"title": "Pictures", "pages": 1, "tiles": [
        {"entity": "camera.porch", "name": "Front porch", "slot": 0, "options": {"size": "square", "display": "live", "refresh": 5}},
        {"entity": "media_player.kitchen", "name": "Kitchen", "slot": 2, "options": {"size": "square", "display": "cover"}},
        {"entity": "camera.garage", "name": "Garage", "slot": 8, "options": {"display": "live"}},
        {"entity": "light.desk", "name": "Desk", "slot": 9},
    ]}, grid)
    player = {"state": "playing", "attributes": {"media_title": "Hejira", "media_artist": "Joni Mitchell", "media_album_name": "Hejira",
              "entity_picture": "/api/media_player_proxy/media_player.kitchen?token=x&cache=abc", "volume_level": 0.4, "supported_features": 0x1FFFFF}}
    ha = {"camera.porch": {"state": "idle", "attributes": {}}, "camera.garage": {"state": "idle", "attributes": {}},
          "media_player.kitchen": player, "light.desk": {"state": "on", "attributes": {"brightness": 200}}}
    tiles = compile_tiles(record["layout"], grid)
    values = [state_message(i, t, ha) for i, t in enumerate(tiles)]
    for v in values:
        if v["entity"] == "media_player.kitchen": v["x"] = media_extras(player["attributes"])
    region = {"clock_24h": False, "numbers": "english", "group_min": 1, "percent_space": False, "keepalive": 30}
    print("sync:", await sender.synchronize("text.kitchen_fire_tile_settings", record, region, values, [[{"k": "clock"}]]), sender.phase)

    async def serve(rounds):
        n = 0
        for _ in range(rounds * 10):
            await asyncio.sleep(0.1)
            while answers:
                req = answers.pop(0); n += 1
                view = int(req["view"])
                if "tiles" in req:
                    count = len(req["tiles"].split(","))
                    atlas = camera_feed.tile_art.parse(req.get("atlas"), (W, H), count)
                    live = camera_feed.live_request(req, atlas=True)
                    print("  live request:", req["tiles"], "idx", req["idx"], "atlas valid", atlas is not None, "request valid", live is not None)
                    right, bottom, frames = atlas
                    im = Image.new("RGB", (right, bottom))
                    for k, (x, y, w, h, rad, shade) in enumerate(frames):
                        ent = req["tiles"].split(",")[k]
                        pic = scene(w, h, k * 40, ent.split(".")[1].upper())
                        if shade:
                            pic = Image.eval(pic, lambda v: v * (255 - shade) // 255)
                        mask = Image.new("L", (w, h), 0); ImageDraw.Draw(mask).rounded_rectangle([0, 0, w - 1, h - 1], rad, fill=255)
                        bg = Image.new("RGB", (w, h), "#" + req["bg"].split(",")[k])
                        im.paste(Image.composite(pic, bg, mask), (x, y))
                    name = f"live{view}.bmp"; im.save(PICS / name, "BMP")
                    reply = {"op": "camera", "t": "live", "e": req["tiles"], "u": f"{BASE}/{name}", "view": view}
                elif "saver" in req:
                    print("  saver request:", req["saver"], req["entity"])
                    name = f"saver{view}.bmp"; Image.eval(scene(W, H, 80, ""), lambda v: v * 200 // 255).save(PICS / name, "BMP")
                    reply = {"op": "camera", "t": "saver", "e": req["entity"], "u": f"{BASE}/{name}", "view": view}
                else:
                    print("  full request:", req["entity"])
                    name = f"full{view}.bmp"; scene(960, 540, 20, req["entity"]).save(PICS / name, "BMP")
                    reply = {"op": "camera", "t": "full", "e": req["entity"], "u": f"{BASE}/{name}", "view": view}
                ok = await sender.auxiliary(reply, session=req.get("session"), revision=req.get("rev"))
                print("    answered:", ok)
        return n

    await command("wait 3", 0.2); await serve(3)
    await command("wait 3", 0.2); await serve(2)
    await command("render 10-pictures")
    await command("hold 200 250", 0.3); await command("wait 3", 0.2); await serve(2)
    await command("wait 3", 0.2); await serve(1)
    await command("render 11-card-camera"); await command("tap 10 10")
    # The screensaver: what's playing, then the clock.
    saver = {"op": "saver", "k": "media", "e": "media_player.kitchen", "n": "Kitchen", "t": "Hejira", "x": {"artist": "Joni Mitchell", "album": "Hejira", "pic": "abc1234567"}}
    print("saver media:", await sender.auxiliary(saver, session=sender.session, revision=sender.confirmed))
    await command("saver 12-saver-media", 0.5); await serve(4); await asyncio.sleep(3)
    saver = {"op": "saver", "k": "clock", "w": "54°", "wi": [{"k": "text", "t": "54°"}, {"k": "text", "i": "F081C", "t": "Back door open"}, {"k": "ago", "t": "Mail", "e": 1761000000}]}
    print("saver clock:", await sender.auxiliary(saver, session=sender.session, revision=sender.confirmed))
    await command("saver 13-saver-clock", 4)
    await cli.disconnect()

asyncio.run(main())
