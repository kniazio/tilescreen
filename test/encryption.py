"""Home Assistant's encryption flow against the screen: adopt, provision over zero-PSK Noise, then encrypted only."""
import asyncio, base64, os, sys
from aioesphomeapi import APIClient, ZERO_NOISE_PSK
from aioesphomeapi.core import RequiresEncryptionAPIError, InvalidEncryptionKeyAPIError

PORT = int(sys.argv[1])

async def info(psk=None):
    cli = APIClient("127.0.0.1", PORT, None, noise_psk=psk, client_info="Home Assistant test")
    await cli.connect(login=True)
    d = await cli.device_info()
    return cli, d

async def main():
    cli, d = await info()
    print("1 plaintext while unprovisioned:", d.name, "encryption supported", d.api_encryption_supported, "provisionable", d.api_encryption_provisionable)
    await cli.disconnect()
    key = base64.b64encode(os.urandom(32))
    cli, d = await info(ZERO_NOISE_PSK)
    print("2 zero-PSK Noise:", d.name, "set_key ->", await cli.noise_encryption_set_key(key))
    await cli.disconnect()
    try:
        cli, d = await info()
        print("3 plaintext after the key: WRONGLY ACCEPTED")
    except RequiresEncryptionAPIError as e:
        print("3 plaintext after the key: refused (RequiresEncryptionAPIError)")
    try:
        cli, d = await info(base64.b64encode(os.urandom(32)).decode())
        print("4 wrong key: WRONGLY ACCEPTED")
    except InvalidEncryptionKeyAPIError as e:
        print("4 wrong key: refused (InvalidEncryptionKeyAPIError), device named", e.received_name)
    cli, d = await info(key.decode())
    print("5 right key:", d.name, "provisionable now", d.api_encryption_provisionable)
    await cli.disconnect()
    print("KEY", key.decode())

asyncio.run(main())
