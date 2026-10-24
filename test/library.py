"""A player's library and a favourite tile, answered with the add-on's own library code (media_library.py)."""
import asyncio, http.server, json, sys, threading
from pathlib import Path
sys.path.insert(0, "/tmp/tessera-ha/screen_manager/app")
sys.path.insert(0, "/home/claude/tilescreen/test")
from aioesphomeapi import APIClient
from PIL import Image, ImageDraw
from core import Grid, state_message, media_extras, validate_favorite
from layout_migrations import migrate_legacy
from page_layout import compile_tiles
from page_delivery import Sender
import camera_feed, media_library
from render import command

PORT, W, H = int(sys.argv[1]), 1024, 600
NOWHERE = "--nowhere" in sys.argv
if NOWHERE: sys.argv.remove("--nowhere")
SAVER = "--saver" in sys.argv
if SAVER: sys.argv.remove("--saver")
import screen_saver
PICS = Path("/tmp/ts-pics"); PICS.mkdir(exist_ok=True)
class Quiet(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *a, **k): super().__init__(*a, directory=str(PICS), **k)
    def log_message(self, *a): pass
server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Quiet)
threading.Thread(target=server.serve_forever, daemon=True).start()
BASE = f"http://127.0.0.1:{server.server_address[1]}"

def raw(title, cls, play, expand, thumb=True, i=0):
    return {"title": title, "media_content_id": f"spotify:{cls}:{i}", "media_content_type": cls, "can_play": play, "can_expand": expand,
            "thumbnail": f"https://i.scdn.co/{i}" if thumb else None, "media_class": cls}
TOP = {"title": "Spotify", "children": [raw(t, "directory", False, True, False, i) for i, t in enumerate(
    ["Playlists", "Artists", "Albums", "Tracks", "Podcasts", "Recently played", "Top artists", "Top tracks"])]}
PLAYLISTS = {"title": "Playlists", "children": [raw(f"Playlist {i + 1}", "playlist", True, True, True, 100 + i) for i in range(14)]}
PLAY = {"id": "spotify:playlist:abc", "type": "playlist", "title": "Morning coffee", "thumb": "https://i.scdn.co/fav", "class": "playlist"}

async def main():
    cli = APIClient("127.0.0.1", PORT, None, client_info="Home Assistant test")
    await cli.connect(login=True)
    _, services = await cli.list_entities_services()
    msg = next(s for s in services if s.name == "screen_message")
    async def send(message):
        r = await cli.execute_service(msg, {"message": json.dumps(message, separators=(",", ":"))}, return_response=True)
        return json.loads(r.response_data)
    sender = Sender(send)
    shelf = media_library.Shelf()
    plays = []
    async def answer(c):
        d = dict(c.data)
        if c.service == "esphome.screen_browse":
            token = int(d["folder"]); item = shelf.get(d["entity"], token) if token else None
            folder = media_library.folder_of(TOP if not token else PLAYLISTS)
            items = media_library.entries(folder, shelf, d["entity"], {"media_playlist": "Playlist 3"})
            pages = media_library.pages(items)
            reply = media_library.message(d["entity"], token, folder["title"], int(d["page"]), pages, len(items))
        elif c.service.startswith("media_player."):
            plays.append((c.service, dict(c.data))); return
        elif c.service == "esphome.screen_play":
            tok = d.get("item")
            plays.append(("tile " + d["tile"]) if d.get("tile") else shelf.get(d["entity"], int(tok))["title"]); return
        elif c.service == "esphome.screen_camera":
            if "saver" in d:
                name = f"saver{d['view']}.bmp"; Image.new("RGB", (W, H), (60, 40, 90)).save(PICS / name, "BMP")
                await sender.auxiliary({"op": "camera", "t": "saver", "e": d["entity"], "u": f"{BASE}/{name}", "view": int(d["view"])},
                                       session=d["session"], revision=d["rev"])
                return
            if "lib" in d:
                count = len(d["lib"].split(","))
                atlas = camera_feed.tile_art.parse(d["atlas"], (W, H), count)
                print("  covers asked:", count, "atlas valid:", atlas is not None)
                right, bottom, frames = atlas
                kind, e = "lib", d["entity"]
            else:
                count = len(d["tiles"].split(","))
                atlas = camera_feed.tile_art.parse(d["atlas"], (W, H), count)
                right, bottom, frames = atlas
                kind, e = "live", d["tiles"]
            im = Image.new("RGB", (right, bottom))
            for k, (x, y, w, h, rad, shade) in enumerate(frames):
                pic = Image.new("RGB", (w, h), ((k * 70) % 255, 120, 200 - (k * 30) % 200))
                ImageDraw.Draw(pic).ellipse([w * .2, h * .2, w * .8, h * .8], fill=(240, 230, 210))
                im.paste(pic, (x, y))
            name = f"{kind}{d['view']}.bmp"; im.save(PICS / name, "BMP")
            reply = {"op": "camera", "t": kind, "e": e, "u": f"{BASE}/{name}"}
        else:
            return
        await sender.auxiliary({**reply, "view": int(d["view"])}, session=d["session"], revision=d["rev"])
    cli.subscribe_service_calls(lambda c: asyncio.ensure_future(answer(c)))
    grid = Grid(4, 3)
    record = migrate_legacy({"title": "Music", "pages": 1, "tiles": [
        {"entity": "media_player.spotify", "name": "Spotify", "slot": 0, "options": {"size": "wide"}},
        {"entity": "media_player.spotify", "name": "Morning coffee", "slot": 2, "options": {"display": "favorite", "play": validate_favorite(PLAY), "size": "square"}},
        {"entity": "media_player.spotify", "name": "Jazz", "slot": 4, "options": {"display": "favorite", "play": {"id": "x", "type": "album", "title": "Jazz", "class": "album"}}},
    ]}, grid)
    attrs = {"source": "Kitchen", "source_list": ["Kitchen", "Office"], "media_title": "Song", "supported_features": 0x1FFFFF, "volume_level": 0.3}
    if NOWHERE:  # Spotify at rest: no speaker, no PLAY_MEDIA
        attrs = {"source_list": ["Kitchen", "Office", "Living room"], "supported_features": 0x1FFFFF & ~512}
    states = {"media_player.spotify": {"state": "paused", "attributes": attrs}}
    compiled = compile_tiles(record["layout"], grid)
    values = []
    for i, t in enumerate(compiled):
        v = state_message(i, t, states)
        if t["options"].get("display") == "favorite":
            v["x"] = {**(media_extras(attrs) or {}), **media_library.player_extras(attrs, browsable=True)}
            v["x"].update(media_library.favorite_extras(t["options"]["play"], None, attrs, None, "Playlist" if "coffee" in t["name"] else "Album"))
        else:
            v["x"] = {**(media_extras(attrs) or {}), **media_library.player_extras(attrs, browsable=True)}
        values.append(v)
    print("x of favourite:", values[1].get("x"), "player:", values[0].get("x"))
    region = {"clock_24h": False, "numbers": "english", "group_min": 1, "percent_space": False, "keepalive": 30}
    print("sync:", await sender.synchronize("text.kitchen_fire_tile_settings", record, region, values, [[{"k": "clock"}]]), sender.phase)
    if SAVER:
        attrs.update({"media_artist": "Joni Mitchell", "media_title": "Coyote", "entity_picture": "/api/media_player_proxy/x?cache=1", "is_volume_muted": False})
        choice = dict(screen_saver.DEFAULT, show=True, media="media_player.spotify", order=["media", "clock"])
        m = screen_saver.message(choice, {"media_player.spotify": {"state": "playing", "attributes": attrs}}, True, lambda t, n: t[:n],
                                 lambda a: media_extras(a) or {}, keys=True)
        print("saver:", m)
        print(" ->", await sender.auxiliary(m, session=sender.session, revision=sender.confirmed))
    for line in sys.argv[2:]:
        await command(line, wait=1.0)
    print("plays:", plays)
    await cli.disconnect()

asyncio.run(main())
