"""Sends the add-on's own clock saver messages (screen_saver.message, faces on) and renders each face."""
import asyncio, sys
sys.path.insert(0, "/home/claude/tilescreen/test")
import render
import screen_saver

async def run():
    cli, calls, command, sender, record, region, values, bars = await render.main()
    states = {"weather.home": {"state": "partlycloudy", "attributes": {"temperature": 54, "temperature_unit": "°F"}}}
    short = lambda t, n: t[:n]
    bar = lambda item: {"k": "text", "i": "F04B9", "t": "Front door locked"}
    for name, choice in [
        ("digital", {}),
        ("glass", {"face": "glass", "numerals": 4}),
        ("glass12", {"face": "glass", "numerals": 12, "look": "light"}),
        ("glass0", {"face": "glass", "numerals": 0, "seconds": False}),
        ("classic", {"face": "classic", "items": [{"entity": "lock.front"}]}),
        ("classic-light", {"face": "classic", "look": "light"}),
        ("bauhaus", {"face": "bauhaus"}),
        ("bauhaus-dark", {"face": "bauhaus", "look": "dark"}),
        ("bauhaus-light", {"face": "bauhaus", "look": "light"}),
    ]:
        c = dict(screen_saver.DEFAULT, show=True, order=["clock"], **choice)
        m = screen_saver.message(c, states, {}, short, lambda a: {}, bar=bar, faces=True)
        print(name, m)
        print(" ->", await sender.auxiliary(m, session=sender.session, revision=sender.confirmed))
        await command("saver face-" + name, wait=5)
    await cli.disconnect()

asyncio.run(run())
