"""Use Android screensaver: the switch, Sleep and Wake handing over to Android, no in-app standby, kept across restarts."""
import asyncio, sys
from aioesphomeapi import APIClient

PORT = int(sys.argv[1]); PHASE = sys.argv[2]

async def main():
    cli = APIClient("127.0.0.1", PORT, None, client_info="Home Assistant test")
    await cli.connect(login=True)
    entities, _ = await cli.list_entities_services()
    by = {e.name: e for e in entities}
    states = {}
    cli.subscribe_states(lambda st: states.__setitem__(st.key, st))
    await asyncio.sleep(0.4)
    sw = by["Use Android screensaver"]
    print("switch:", type(sw).__name__, sw.object_id, "category", sw.entity_category, "state", states[sw.key].state)
    if PHASE == "on":
        cli.switch_command(sw.key, True); await asyncio.sleep(0.4)
        print("after on:", states[sw.key].state)
        cli.number_command(by["Standby after"].key, 60); cli.switch_command(by["Auto standby"].key, True)
        cli.button_command(by["Sleep"].key); await asyncio.sleep(0.4)
        cli.button_command(by["Wake"].key); await asyncio.sleep(0.4)
        print("waiting 65 s for an in-app standby that must not come")
        await asyncio.sleep(65)
    await cli.disconnect()
asyncio.run(main())
