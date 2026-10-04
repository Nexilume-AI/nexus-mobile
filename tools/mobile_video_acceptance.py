"""Real AVD + existing Cloud + real Chromium; no extra API/frontend process.

Invoked by real_android_emulator.py --cloud-video. Reuses AndroidAVD's real
pairing, UI and Accessibility helpers. Credentials remain in memory only.
"""
from __future__ import annotations

import json
import os
from pathlib import Path
import re
import secrets
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[2]


def acceptance(*, serial, apk, web_url="http://127.0.0.1:5173", report=None, headed=True, network=None, sdk_actions_only=False):
    sys.path.insert(0, str(ROOT / "nexus_server"))
    sys.path.insert(0, str(ROOT))
    os.environ.setdefault("DJANGO_SETTINGS_MODULE", "config.settings")
    import django
    django.setup()
    from django.contrib.auth import get_user_model
    from django.utils import timezone
    from playwright.sync_api import sync_playwright, expect
    from rest_framework.test import APIClient
    from apps.mobile.models import MobileDevice, MobileVideoSession
    from apps.tenancy.models import Membership, Tenant
    from tests.agent_execution_helpers import grant_mobile_test_capacity
    from nexus_mobile.tools.hosted_mobile_avd import AndroidAVD, PACKAGE

    avd = AndroidAVD(name="existing-video-avd", serial=serial, port=int(serial.split("-")[-1]), sdk_root=Path("D:/Android/Sdk"))
    if avd.adb_run("shell", "getprop", "ro.kernel.qemu").stdout.strip() != "1":
        raise RuntimeError("ANDROID_EMULATOR_REQUIRED")
    suffix = uuid4().hex[:10]
    if report:
        Path(report).parent.mkdir(parents=True, exist_ok=True)
    password = secrets.token_urlsafe(32)
    user = get_user_model().objects.create_user(username=f"videoqa_{suffix}", email=f"videoqa-{suffix}@example.test", password=password)
    tenant = Tenant.objects.create(name="Mobile Video QA", slug=f"mobile-video-qa-{suffix}")
    Membership.objects.create(tenant=tenant, user=user, role="owner")
    grant_mobile_test_capacity(tenant)
    device = None
    browser = None
    reverse_created = False
    result = {"status": "running", "organization": tenant.name, "web_port": 5173, "real_android": True, "real_webrtc": not sdk_actions_only,
        "network_scope": network["network_scope"] if network else "local-emulator", "browser_mdns_disabled_for_fixture": not sdk_actions_only and not bool(network), "public_nat_acceptance": False}
    print("Mobile Video QA: installing and pairing the real emulator", flush=True)
    try:
        avd.install(Path(apk))
        permissions = avd.adb_run("shell", "dumpsys", "package", PACKAGE).stdout
        if "android.permission.ACCESS_NETWORK_STATE: granted=true" not in permissions:
            raise RuntimeError("VIDEO_APK_NETWORK_PERMISSION_MISSING")
        if "MediaProjectionPermissionActivity" in avd.adb_run("shell", "dumpsys", "activity", "activities").stdout:
            avd.adb_run("shell", "input", "keyevent", "4")
        avd.adb_run("shell", "input", "keyevent", "3")
        client = APIClient(); client.force_authenticate(user)
        response = client.post("/api/v1/mobile-devices/", {"name": "Video QA Android", "approval_mode": "confirm_high_risk"},
            format="json", HTTP_X_NEXUS_TENANT=str(tenant.id))
        if response.status_code != 201:
            raise RuntimeError(f"VIDEO_FIXTURE_CREATE_FAILED: {response.status_code}")
        data = response.json()["data"]
        device = MobileDevice.objects.get(id=data["id"])
        # Debug APK supports emulator-loopback HTTP. Release remains HTTPS-only.
        # This does not disable certificate checks or modify any phone trust store.
        mappings = avd.adb_run("reverse", "--list").stdout
        existing_mapping = [line.split() for line in mappings.splitlines() if "tcp:8000" in line.split()]
        if existing_mapping and any(line[-2:] != ["tcp:8000", "tcp:8000"] for line in existing_mapping):
            raise RuntimeError("VIDEO_ADB_REVERSE_CONFLICT")
        if not existing_mapping:
            avd.adb_run("reverse", "tcp:8000", "tcp:8000")
            reverse_created = True
        # Keep Accessibility alive throughout pairing. UIAutomator takes its
        # own automation connection and can leave Android's real service with
        # a null active window on repeated runs. The debug read-only inspector
        # observes the same native UI without replacing that connection.
        avd.preserve_accessibility = True
        avd.ensure_accessibility()
        avd.wait_for_text_recovering_system_ui("Nexus Mobile", timeout=60)
        avd.pair(base_url="http://127.0.0.1:8000", device_id=str(device.id), token=data["pairing_token"])
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            device.refresh_from_db()
            if device.lifecycle_status == "online" and device.capabilities.get("live_video") == 1: break
            time.sleep(1)
        else: raise RuntimeError("VIDEO_PHONE_NOT_ONLINE")
        avd.preserve_accessibility = True
        # UIAutomator used by pairing suppresses/rebinds Accessibility. Rebind
        # only this disposable AVD after the last automation connection, then
        # require an actual native window before testing MediaProjection.
        avd.adb_run("shell", "settings", "put", "secure", "accessibility_enabled", "0")
        time.sleep(.5)
        avd.ensure_accessibility()
        avd.wait_for_text_recovering_system_ui("Nexus Mobile", timeout=60)
        if sdk_actions_only:
            result.update(_sdk_actions(avd=avd, user=user, tenant=tenant, device=device, client=client))
            result["status"] = "passed"
            return result
        print("Mobile Video QA: phone online; opening web on port 5173", flush=True)

        with sync_playwright() as playwright:
            # Emulator NAT cannot resolve a host browser's .local mDNS candidates.
            # Expose ordinary host candidates only in this disposable QA browser;
            # do not weaken production TURN's private-peer/SSRF restrictions.
            browser = playwright.chromium.launch(headless=not headed,
                args=[] if network else ["--disable-features=WebRtcHideLocalIpsWithMdns"])
            context = browser.new_context(viewport={"width": 1440, "height": 1000})
            context.add_init_script("localStorage.setItem('nexus.locale','en-US')")
            page = context.new_page()
            events = []
            viewer_diagnostics = []
            page.on("console", lambda message: viewer_diagnostics.append(message.text)
                    if message.text.startswith("Mobile video closed:") else None)
            def state_changed(response):
                if "/mobile-video/" in response.url:
                    try:
                        payload = response.json().get("data", {})
                        events.append({"id": payload.get("id"), "state": payload.get("state"), "error": payload.get("error_code"), "status": response.status})
                    except Exception: pass
            page.on("response", state_changed)
            page.goto(web_url + "/login")
            page.get_by_label("Email", exact=True).fill(user.email)
            page.get_by_label("Password", exact=True).fill(password)
            page.get_by_role("button", name="Sign in securely", exact=True).click()
            expect(page.get_by_role("button", name="Open account menu")).to_be_visible(timeout=30000)
            page.goto(web_url + "/mobile")
            row = page.get_by_role("row").filter(has_text="Video QA Android")
            row.get_by_role("button").first.click()
            page.get_by_role("navigation", name="Device workspace sections").get_by_role("button", name="Control", exact=True).click()
            live = page.get_by_role("region", name="Phone screen", exact=True)
            live.get_by_role("button", name="Start live video", exact=True).click()
            print("Mobile Video QA: requesting Android projection consent", flush=True)
            expect(live.get_by_text("Open Nexus Mobile on your phone", exact=False)).to_be_visible(timeout=15000)
            def phone_consent():
                avd.adb_run("shell", "am", "start", "-n", f"{PACKAGE}/.MainActivity")
                consent_error = ""
                for attempt in range(5):
                    try:
                        avd.tap_text("Share screen", timeout=4); break
                    except RuntimeError as error:
                        consent_error = str(error)
                        width, height = avd.screen_size()
                        avd.adb_run("shell", "input", "swipe", str(width // 2), str(int(height * .82)), str(width // 2), str(int(height * .3)), "400")
                else:
                    if report:
                        avd.adb_run("shell", "screencap", "-p", "/sdcard/nexus-video-ui.png")
                        avd.adb_run("pull", "/sdcard/nexus-video-ui.png", str(Path(report).with_name("phone-ui.png")))
                        avd.adb_run("shell", "rm", "/sdcard/nexus-video-ui.png")
                    raise RuntimeError("VIDEO_CONSENT_BUTTON_UNAVAILABLE: " + consent_error + "; states=" + json.dumps(events[-8:]) + "; viewer=" + json.dumps(viewer_diagnostics[-4:]))
                avd.wait_for_node(lambda node: node.get("package") == "com.android.systemui" and
                    "Start recording or casting with Nexus Mobile" in node.get("text", ""), timeout=25)
                buttons = list(avd.dump_ui().iter("node"))
                positive = next((n for n in buttons if n.get("resource-id") == "android:id/button1"), None)
                if positive is not None:
                    avd._tap_node(positive, "Android screen-sharing consent")
                else:
                    # API 34 hides its affirmative projection button from ordinary
                    # Accessibility. This fixture's actual 1080x2400 native dialog
                    # was visually inspected: Start now occupies x733..974,y1534..1630.
                    # Tap that real button; do not forge a grant or change app-ops.
                    assert any(n.get("text") == "Cancel" for n in buttons)
                    if avd.screen_size() != (1080, 2400): raise RuntimeError("VIDEO_CONSENT_UI_UNSUPPORTED")
                    avd.adb_run("shell", "input", "tap", "854", "1582")
                deadline = time.monotonic() + 15
                while time.monotonic() < deadline:
                    if PACKAGE in avd.adb_run("shell", "dumpsys", "media_projection").stdout: break
                    time.sleep(.25)
                else: raise RuntimeError("VIDEO_NATIVE_PROJECTION_NOT_STARTED: " + json.dumps(viewer_diagnostics))
                # Do not clear the consent Activity until its projection is running.
                avd.adb_run("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity")
                for attempt in range(5):
                    try:
                        avd.tap_text("Open Mobile E2E Surface", timeout=4)
                        avd.wait_for_text("Nexus Mobile E2E", timeout=4)
                        break
                    except RuntimeError:
                        width, height = avd.screen_size()
                        avd.adb_run("shell", "input", "swipe", str(width // 2), str(int(height * .82)), str(width // 2), str(int(height * .3)), "400")
                else: raise RuntimeError("VIDEO_TEST_SURFACE_UNAVAILABLE")
            # Keep Playwright's synchronous event pump alive during ADB waits.
            # All Android actions still use the real UI and native consent prompt.
            with ThreadPoolExecutor(max_workers=1) as phone_worker:
                consent_result = phone_worker.submit(phone_consent)
                while not consent_result.done(): page.wait_for_timeout(100)
                consent_result.result()
            video = live.locator("video")
            deadline = time.monotonic() + 45
            while time.monotonic() < deadline:
                if video.count() == 0:
                    text = live.get_by_role("alert").text_content() if live.get_by_role("alert").count() else "Viewer closed"
                    raise RuntimeError("VIDEO_VIEWER_FAILED: " + text + "; states=" + json.dumps(events[-12:]))
                if int(video.get_attribute("data-frames-decoded") or 0) >= 5: break
                page.wait_for_timeout(500)
            else:
                raise RuntimeError("VIDEO_NO_RTP_FRAMES: states=" + json.dumps(events[-12:]))
            if network:
                expect(video).to_have_attribute("data-local-candidate", "relay", timeout=15000)
                expect(video).to_have_attribute("data-remote-candidate", "relay", timeout=15000)
                assert int(video.get_attribute("data-received-bytes") or 0) > 0
                result["selected_candidates"] = ["relay", "relay"]
                result["security_rejections"] = network["security_rejections"]
                result["internal_network"] = network["internal_network"]
                result["udp_blocked_tcp_fallback"] = network["tcp_only_listener"]
                expect(video).to_have_attribute("data-relay-protocol", "tcp", timeout=15000)
                result["relay_received_bytes"] = int(video.get_attribute("data-received-bytes"))
                print("NAT QA: real relay/relay RTP decoded with default browser mDNS", flush=True)
                before_interruption = int(video.get_attribute("data-frames-decoded"))
                with ThreadPoolExecutor(max_workers=1) as worker:
                    interrupted = worker.submit(network["brief_interruption"])
                    while not interrupted.done(): page.wait_for_timeout(100)
                    interrupted.result()
                deadline = time.monotonic() + 20
                while time.monotonic() < deadline:
                    if int(video.get_attribute("data-frames-decoded") or 0) > before_interruption + 5: break
                    page.wait_for_timeout(500)
                else: raise RuntimeError("VIDEO_RELAY_INTERRUPTION_NOT_RECOVERED")
                result["brief_relay_interruption_recovered"] = True
            print("Mobile Video QA: real RTP frames decoded; testing browser gestures", flush=True)
            expect(live.get_by_role("button", name="Control live screen")).to_be_enabled(timeout=15000)
            live.get_by_role("button", name="Control live screen").click()
            before = int(video.get_attribute("data-frames-decoded") or 0)
            initial_image = video.screenshot()
            width, height = avd.screen_size()

            def click_native(label, *, hold=False):
                node = avd.wait_for_node(lambda n: n.get("text", "").casefold() == label.casefold(), timeout=10)
                bounds = [int(v) for v in re.findall(r"\d+", node.get("bounds", ""))]
                px, py = (bounds[0] + bounds[2]) / 2 / width, (bounds[1] + bounds[3]) / 2 / height
                box = video.bounding_box(); size = video.evaluate("v => ({w:v.videoWidth,h:v.videoHeight})")
                scale = min(box["width"] / size["w"], box["height"] / size["h"])
                x = box["x"] + (box["width"] - size["w"] * scale) / 2 + px * size["w"] * scale
                y = box["y"] + (box["height"] - size["h"] * scale) / 2 + py * size["h"] * scale
                page.mouse.move(x, y); page.mouse.down(); page.wait_for_timeout(850 if hold else 70); page.mouse.up()

            marker = "Video-" + suffix
            # Prefill test text via native UI; the verified effect is a browser-origin gesture.
            avd.tap_text("E2E message")
            avd.adb_run("shell", "input", "keycombination", "113", "29")  # Ctrl+A
            avd.adb_run("shell", "input", "text", marker)
            avd.adb_run("shell", "input", "keyevent", "4")
            click_native("Apply")
            avd.wait_for_text("Saved: " + marker, timeout=30)
            effects = avd.adb_run("shell", "run-as", PACKAGE, "cat", "files/paper-mobile-effects.jsonl").stdout.splitlines()
            assert any(json.loads(line).get("marker") == marker for line in effects)
            page.wait_for_timeout(1500)
            assert int(video.get_attribute("data-frames-decoded") or 0) > before
            assert video.screenshot() != initial_image
            expect(live.get_by_role("button", name="Stop screen control")).to_be_enabled()
            page.wait_for_timeout(2500)
            click_native("Apply", hold=True)
            avd.wait_for_text("Long press received", timeout=30)
            page.wait_for_timeout(2500)
            # Scroll the real native ScrollView through a browser-origin drag.
            # Inspect UI bounds independently of the Cloud action result.
            before_node = avd.wait_for_node(lambda n: n.get("text") == "Scrollable target 1", timeout=10)
            before_bounds = before_node.get("bounds")
            box = video.bounding_box()
            page.mouse.move(box["x"] + box["width"] / 2, box["y"] + box["height"] * .8)
            page.mouse.down(); page.mouse.move(box["x"] + box["width"] / 2, box["y"] + box["height"] * .25, steps=20); page.mouse.up()
            deadline = time.monotonic() + 20
            while time.monotonic() < deadline:
                nodes = list(avd.dump_ui().iter("node"))
                if not any(n.get("text") == "Scrollable target 1" and n.get("bounds") == before_bounds for n in nodes): break
                page.wait_for_timeout(500)
            else: raise RuntimeError("VIDEO_BROWSER_SWIPE_NO_NATIVE_EFFECT")
            result["browser_swipe_effect"] = True
            result.update(status="passed", device_id=str(device.id), frames_decoded=int(video.get_attribute("data-frames-decoded")),
                video_dimensions=video.evaluate("v => [v.videoWidth,v.videoHeight]"), browser_tap_effect=True, browser_long_press_effect=True)
            if report:
                Path(report).parent.mkdir(parents=True, exist_ok=True)
                page.screenshot(path=str(Path(report).with_suffix(".png")))
                page.set_viewport_size({"width": 390, "height": 844})
                page.screenshot(path=str(Path(report).with_name("phone-mobile.png")))
                assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth")
                result["mobile_no_overflow"] = True
                page.set_viewport_size({"width": 1440, "height": 1000})
            live.get_by_role("button", name="Stop video", exact=True).click()
            deadline = time.monotonic() + 15
            while time.monotonic() < deadline:
                state = avd.adb_run("shell", "dumpsys", "media_projection").stdout
                if "com.nexus.mobile" not in state: break
                page.wait_for_timeout(500)
            else: raise RuntimeError("VIDEO_PROJECTION_NOT_STOPPED")
            video_session_id = next(e["id"] for e in reversed(events) if e.get("id"))
            sessions_stopped = page.request.get(web_url + f"/api/v1/mobile-video/{video_session_id}/", headers={"X-Nexus-Tenant": str(tenant.id)})
            assert sessions_stopped.json()["data"]["state"] in ["stopped", "failed", "expired"]
            result["projection_cleanup"] = True
            # The same phone shell must also display a real protected capture.
            live.get_by_label("Screen mode").select_option("capture")
            live.get_by_role("button", name="Capture screen", exact=True).click()
            captured = live.get_by_alt_text("Latest Android screen capture")
            expect(captured).to_be_visible(timeout=30000)
            deadline = time.monotonic() + 15
            while time.monotonic() < deadline:
                if captured.evaluate("i => i.complete && i.naturalWidth > 0"): break
                page.wait_for_timeout(300)
            else: raise RuntimeError("VIDEO_CAPTURE_PREVIEW_NOT_LOADED")
            result["protected_capture_in_same_phone"] = True
            assert page.get_by_test_id("mobile-phone-frame").count() == 1
            context.close(); browser.close(); browser = None
    except Exception as exc:
        result.update(status="failed", error_type=type(exc).__name__)
        raise
    finally:
        avd.adb_run("shell", "am", "force-stop", PACKAGE, check=False)
        if reverse_created: avd.adb_run("reverse", "--remove", "tcp:8000", check=False)
        if browser:
            try: browser.close()
            except Exception: pass  # The Playwright context already closes it on failure.
        if device:
            MobileVideoSession.objects.filter(device=device).update(state="stopped", signals=[])
            device.status = "deleted"; device.token_hash = secrets.token_hex(32); device.last_observation = {}; device.save()
        user.is_active = False; user.save(update_fields=["is_active"])
        tenant.status = "deleted"; tenant.save(update_fields=["status"])
        if report:
            Path(report).write_text(json.dumps(result, indent=2), encoding="utf-8")
    return result


def _sdk_actions(*, avd, user, tenant, device, client):
    """Real SDK HTTP delegate, genuine pairing/approval and native effects.

    Reuses the existing acceptance fixture and formal Cloud. No fake device
    results, new API process or credential files are used.
    """
    import asyncio
    import hashlib
    from django.utils import timezone
    from rest_framework.test import APIRequestFactory
    from apps.agents.models import Agent, AgentDisplayRun, AgentRunInteraction, AgentMobileGrant
    from apps.agents import mobile_access
    from apps.common.subjects import hash_token
    from nexus_mobile.tools.hosted_mobile_avd import PACKAGE
    sys.path.insert(0, str(ROOT / "nexus_openwrt/sdk/nexus-agent-sdk-python/src"))
    from nexus_agent import NexusRunContext, MOBILE_SCOPES
    from nexus_agent.reporting import NexusMobilePermissionRequired, NexusMobileUnavailable
    from datetime import timedelta

    scopes = list(MOBILE_SCOPES)
    agent = Agent.objects.create(tenant=tenant, created_by=user, name="SDK Mobile action QA",
        mobile_requirement="required", mobile_capabilities=scopes)
    request = APIRequestFactory().post("/mcp", HTTP_X_NEXUS_TENANT=str(tenant.id))
    request.user = user; request.tenant_id = str(tenant.id)
    mobile_access.set_mobile_grant(request=request, agent=agent, scopes=scopes)
    binding = mobile_access.create_mobile_binding(request=request, agent_id=str(agent.id), device_id=str(device.id))
    token, digest, expiry = mobile_access.issue_mobile_delegate_token(capabilities=scopes)
    display_token = secrets.token_urlsafe(32)
    run = AgentDisplayRun.objects.create(tenant=tenant, agent=agent, consumer_tenant=tenant,
        caller_subject_hash=binding.caller_subject_hash, caller_principal_type="user", caller_principal_id=str(user.id),
        run_kind="invocation", status="running", interaction_mode="task", mobile_binding=binding,
        mobile_capabilities_snapshot=scopes, mobile_delegate_token_hash=digest, mobile_delegate_token_expires_at=expiry,
        display_token_hash=hash_token(display_token), display_token_expires_at=timezone.now() + timedelta(minutes=10),
        write_token=secrets.token_urlsafe(32))
    ctx = NexusRunContext(run_id=str(run.id), mobile_enabled=True, mobile_capabilities=scopes,
        mobile_delegate_url=f"http://127.0.0.1:8000/api/v1/internal/agent-runs/{run.id}/mobile/", mobile_delegate_token=token)
    completed = False
    try:
        print("Mobile SDK QA: using real caller-bound Run delegate", flush=True)
        status = ctx.mobile.status()
        assert status.available and {"long_press", "press_home", "press_recents"} <= set(status.supported_actions)
        ctx.mobile.open_app(PACKAGE)
        ctx.mobile.tap_text("Open Mobile E2E Surface")
        ctx.mobile.wait_for_state(text="Nexus Mobile E2E", timeout=30)
        assert "Nexus Mobile E2E" in str(ctx.mobile.observe().data)
        ctx.mobile.tap_text("E2E message")
        marker = "SDK-中文-" + uuid4().hex[:8]
        with ThreadPoolExecutor(max_workers=1) as worker:
            typed = worker.submit(ctx.mobile.type_text, marker, timeout=60)
            deadline = time.monotonic() + 20
            while time.monotonic() < deadline:
                interaction = AgentRunInteraction.objects.filter(run=run, status="pending").first()
                if interaction: break
                if typed.done(): typed.result()
                time.sleep(.2)
            else: raise RuntimeError("SDK_MOBILE_APPROVAL_NOT_REQUESTED")
            # Caller reply goes through the normal authenticated Display API.
            approved = client.post(f"/api/v1/agent-runs/{run.id}/interactions/{interaction.id}/reply/",
                {"value": "approve"}, format="json", HTTP_X_NEXUS_TENANT=str(tenant.id),
                HTTP_X_NEXUS_AGENT_DISPLAY_TOKEN=display_token)
            if approved.status_code != 200: raise RuntimeError(f"SDK_MOBILE_APPROVAL_FAILED:{approved.status_code}")
            typed.result()
        ctx.mobile.press_back()
        ctx.mobile.wait_for_state(text="Apply", timeout=30)
        node = avd.wait_for_node(lambda n: n.get("text", "").casefold() == "apply", timeout=15)
        bounds = [int(v) for v in re.findall(r"\d+", node.get("bounds", ""))]
        x, y = (bounds[0] + bounds[2]) / 2, (bounds[1] + bounds[3]) / 2
        ctx.mobile.tap(x=x, y=y, coordinate_space="pixels")
        avd.wait_for_text("Saved: " + marker, timeout=30)
        asyncio.run(ctx.aio.mobile.long_press(x=x, y=y, coordinate_space="pixels", duration_ms=750))
        avd.wait_for_text("Long press received", timeout=30)
        before = avd.wait_for_node(lambda n: n.get("text") == "Scrollable target 1", timeout=10).get("bounds")
        ctx.mobile.swipe(.5, .8, .5, .25, coordinate_space="normalized", duration_ms=450)
        nodes = list(avd.dump_ui().iter("node"))
        assert not any(n.get("text") == "Scrollable target 1" and n.get("bounds") == before for n in nodes)
        screen = asyncio.run(ctx.aio.mobile.capture_screen())
        assert screen.width > 0 and screen.height > 0 and len(screen.content) > 100
        asyncio.run(ctx.aio.mobile.press_home())
        assert asyncio.run(ctx.aio.mobile.observe()).data.get("packageName") != PACKAGE
        asyncio.run(ctx.aio.mobile.press_recents())
        # Pixel/AOSP gesture navigation hosts Recents inside the launcher,
        # not necessarily a dedicated SystemUI RecentsActivity. Inspect the
        # actual overview panel rather than assuming a process name.
        avd.wait_for_node(lambda n: n.get("resource-id", "").endswith(":id/overview_panel"), timeout=15)
        ctx.mobile.press_back()
        ctx.mobile.open_app(PACKAGE)
        # Authorization is checked by Cloud, even if an SDK supplies old scopes.
        run.mobile_capabilities_snapshot = ["mobile.observe"]
        run.save(update_fields=["mobile_capabilities_snapshot"])
        try: ctx.mobile.press_home()
        except NexusMobilePermissionRequired: pass
        else: raise RuntimeError("SDK_MOBILE_SCOPE_BYPASS")
        ctx._mobile_delegate_token = "invalid-token"
        try: ctx.mobile.observe()
        except NexusMobileUnavailable: pass
        else: raise RuntimeError("SDK_MOBILE_TOKEN_BYPASS")
        completed = True
        return {"real_sdk_delegate": True, "run_id": str(run.id), "sdk_source": "workspace",
            "actions": ["status", "open_app", "tap_text", "observe", "wait_for_state", "type_text", "press_back",
                        "tap_coordinates", "long_press", "swipe", "capture_screen", "press_home", "press_recents"],
            "caller_approval": True, "unicode_input_verified": True, "scope_rejection": True, "token_rejection": True,
            "screenshot": {"width": screen.width, "height": screen.height, "sha256": hashlib.sha256(screen.content).hexdigest()},
            "sync_and_async": True}
    finally:
        mobile_access.close_mobile_run(run=run)
        run.status = "completed" if completed else "failed"
        run.mobile_delegate_token_hash = ""; run.display_token_hash = ""; run.write_token = ""
        run.completed_at = timezone.now(); run.save()
        binding.status = "deleted"; binding.save(update_fields=["status"])
        AgentMobileGrant.objects.filter(agent=agent).update(status="deleted")
        agent.status = "deleted"; agent.save(update_fields=["status"])
