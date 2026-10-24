"""Tessera's screen settings, checked against the add-on's own tables and driven the way Home Assistant drives them."""
import asyncio, datetime, sys
sys.path.insert(0, "/tmp/tessera-ha/screen_manager/app")
from aioesphomeapi import APIClient, NumberInfo, SwitchInfo, SelectInfo, ButtonInfo, TimeInfo
from core import SETTING_ENTITIES, OWNED_SETTINGS_MARKERS, SCREEN_ENTITY_NAMES

PORT = int(sys.argv[1]); PHASE = sys.argv[2]
TYPES = {"number": NumberInfo, "switch": SwitchInfo, "select": SelectInfo, "time": TimeInfo}

async def main():
    cli = APIClient("127.0.0.1", PORT, None, client_info="Home Assistant test")
    await cli.connect(login=True)
    entities, _ = await cli.list_entities_services()
    by = {e.name: e for e in entities}
    states = {}
    cli.subscribe_states(lambda st: states.__setitem__(st.key, st))
    await asyncio.sleep(0.4)
    def value(name):
        st = states.get(by[name].key)
        if isinstance(by[name], TimeInfo): return f"{st.hour:02d}:{st.minute:02d}"
        return st.state
    if PHASE == "check":
        missing = [k for k, (kind, name) in SETTING_ENTITIES.items() if name not in by or not isinstance(by[name], TYPES[kind])]
        print("Tessera's setting entities, all present with the right type:", not missing, missing or "")
        print("owned-settings markers Tessera looks for:", sorted(n for n in OWNED_SETTINGS_MARKERS if n in by))
        print("Wake and Sleep buttons:", isinstance(by.get("Wake"), ButtonInfo), isinstance(by.get("Sleep"), ButtonInfo))
        print("custom entities gone:", not any(n in by for n in ("Screen", "Screensaver brightness", "Screen brightness")))
        print("ranges:", {n: (by[n].min_value, by[n].max_value, by[n].step, by[n].unit_of_measurement) for n in ("Normal brightness", "Standby after", "Back to page 1 after")})
        print("rotation options:", by["Rotation"].options, "| defaults:", {n: value(n) for n in ("Normal brightness", "Auto standby", "Standby after", "Standby brightness", "Night mode", "Night starts", "Night ends", "Night brightness", "Dark mode", "Swipe between pages")})
        # Changes, as Tessera makes them with each entity's own action.
        cli.number_command(by["Normal brightness"].key, 60)
        cli.number_command(by["Standby brightness"].key, 90)   # clamps to the brightness
        cli.number_command(by["Standby after"].key, 60)
        cli.switch_command(by["Night mode"].key, True)
        now = datetime.datetime.now()
        start = (now - datetime.timedelta(minutes=5)); end = (now + datetime.timedelta(minutes=30))
        cli.time_command(by["Night starts"].key, start.hour, start.minute, 0)
        cli.time_command(by["Night ends"].key, end.hour, end.minute, 0)
        cli.number_command(by["Night brightness"].key, 3)
        cli.select_command(by["Rotation"].key, "180°")
        cli.switch_command(by["Dark mode"].key, False)
        await asyncio.sleep(0.6)
        print("after changes:", {n: value(n) for n in ("Normal brightness", "Standby brightness", "Standby after", "Night starts", "Night ends", "Night brightness", "Rotation", "Dark mode")})
        cli.number_command(by["Normal brightness"].key, 2)    # clamps to 5, and pulls the dim levels down
        await asyncio.sleep(0.4)
        print("brightness 2 ->", value("Normal brightness"), "standby", value("Standby brightness"), "night", value("Night brightness"))
        cli.number_command(by["Normal brightness"].key, 60)
        cli.number_command(by["Standby brightness"].key, 25)
        cli.number_command(by["Night brightness"].key, 3)
        await asyncio.sleep(0.4)
        print("Sleep (night hours now):"); cli.button_command(by["Sleep"].key); await asyncio.sleep(0.5)
        print("Wake:"); cli.button_command(by["Wake"].key); await asyncio.sleep(0.5)
        cli.switch_command(by["Night mode"].key, False); await asyncio.sleep(0.3)
        print("waiting 63 s for Auto standby (Standby after 60 s, night off)..."); await asyncio.sleep(63)
        print("Wake:"); cli.button_command(by["Wake"].key); await asyncio.sleep(0.5)
    else:
        print("after a restart:", {n: value(n) for n in ("Normal brightness", "Standby brightness", "Standby after", "Night mode", "Night brightness", "Rotation", "Dark mode")})
    await cli.disconnect()

asyncio.run(main())
