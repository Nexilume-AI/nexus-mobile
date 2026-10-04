"""Temporary NAT topology for the existing Cloud's managed TURN, never a second API.

RFC 2544 addresses exist only in a disposable internal Docker network. Browser
and emulator reach its published listener through Docker DNAT; relay-to-relay
traffic stays inside that network. Private-peer deny rules remain unchanged.
The official configuration, identity and pinned image are restored in finally.
"""
from contextlib import contextmanager
import hashlib
import json
from pathlib import Path
import secrets
import sys
import time

ROOT = Path(__file__).resolve().parents[2]


@contextmanager
def managed_nat():
    sys.path.insert(0, str(ROOT / "nexus_server/scripts"))
    import cloud_turn as turn
    state = ROOT / ".local/nexus-cloud/turn"
    with turn.lifecycle_lock(state):
        owned = turn.owned_containers(state)
        if len(owned) != 1 or not json.loads(turn.docker("inspect", owned[0]))[0]["State"]["Running"]:
            raise RuntimeError("NAT_REQUIRES_RUNNING_OFFICIAL_TURN")
        originals = {name: (state / name).read_text(encoding="utf-8") for name in ("settings.json", "turnserver.conf", "compose.json")}
        config = json.loads(originals["settings.json"])
        image = json.loads((state / "image.json").read_text())["image"]
        octet = 20 + secrets.randbelow(200)
        relay = f"198.18.{octet}.2"
        identity = hashlib.sha256(str(state).encode()).hexdigest()[:20]
        network = f"nexus-turn-{identity}_mobile-nat"
        try:
            print("NAT QA: temporarily isolating the existing managed TURN; no host firewall changes", flush=True)
            # Keep Cloud's listener URLs and signing identity unchanged.
            spec = turn.options(**{**config, "external_ip": relay})
            turn.prepare(state, spec)
            # Docker Desktop cannot publish a listener on an internal-only
            # bridge. Keep its existing control bridge for inbound DNAT, and
            # bind media exclusively to the isolated relay interface.
            turn.private_write(state / "turnserver.conf", (state / "turnserver.conf").read_text() + f"relay-ip={relay}\nno-udp\n")
            service = turn.service(state, spec, image)
            service["networks"] = {"default": {}, "mobile-nat": {"ipv4_address": relay}}
            turn.private_write(state / "compose.json", {"services": {"turn": service}, "networks": {
                "default": {}, "mobile-nat": {"internal": True, "ipam": {"config": [{"subnet": f"198.18.{octet}.0/24"}]}}}})
            turn.compose(state, "up", "-d", "--wait", "--wait-timeout", "30")
            record = json.loads(turn.docker("inspect", turn.owned_containers(state)[0]))[0]
            assert record["NetworkSettings"]["Networks"][network]["IPAddress"] == relay
            assert json.loads(turn.docker("network", "inspect", network))[0]["Internal"] is True
            from turn_probe import verify_allocation, verify_rejections
            secret = (state / "secret").read_text().strip()
            print("NAT QA: checking TCP allocation through the published NAT listener", flush=True)
            verify_allocation("127.0.0.1", config["port"], secret, protocol="tcp")
            verify_rejections("127.0.0.1", config["port"], secret, protocol="tcp")

            def interrupt():
                container = turn.owned_containers(state)[0]
                turn.docker("pause", container)
                try: time.sleep(2)
                finally: turn.docker("unpause", container)

            yield {"brief_interruption": interrupt, "network_scope": "isolated-docker-nat-and-emulator",
                   "internal_network": True, "tcp_only_listener": True,
                   "security_rejections": ["anonymous", "wrong_credential", "expired_credential", "loopback", "private_network", "metadata_address"]}
        finally:
            for name, content in originals.items(): turn.private_write(state / name, content)
            turn.compose(state, "up", "-d", "--wait", "--wait-timeout", "30")
            turn.check(state)
            # The recreated official container is back on its original network.
            networks = turn.docker("network", "ls", "--filter", "name=^" + network + "$", "--format", "{{.Name}}").splitlines()
            if network in networks:
                info = json.loads(turn.docker("network", "inspect", network))[0]
                if info["Containers"] or info.get("Labels", {}).get("com.docker.compose.project") != "nexus-turn-" + identity:
                    raise RuntimeError("NAT_NETWORK_CLEANUP_OWNERSHIP_FAILED")
                turn.docker("network", "rm", network)
            print("NAT QA: official TURN restored and isolated network removed", flush=True)
