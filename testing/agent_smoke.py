"""Offline Compose smoke: real Java auth gateway + Agent persistence, no provider calls."""
import json
import time
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8080"


def call(path, body=None, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["token"] = token
    request = urllib.request.Request(BASE + path,
        data=json.dumps(body).encode() if body is not None else None, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.load(error)


for attempt in range(90):
    try:
        status, login = call("/api/auth/login", {"operatorId": 1024, "password": "pulseflow-local"})
        if status == 200 and login["code"] == 200:
            break
    except (OSError, ValueError):
        pass
    time.sleep(2)
else:
    raise AssertionError("Java gateway did not become ready")

token = login["data"]["tokenValue"]
assert call("/api/investigations", {"question": "Investigate growth"})[0] == 401
assert call("/api/investigations", {"question": "Investigate userId 123456"}, token)[0] == 422
status, response = call("/api/investigations", {"question": "Investigate aggregate conversion"}, token)
assert status == 200 and response["code"] == 200
id = response["data"]["id"]
path = "/api/investigations/" + id
for _ in range(30):
    status, result = call(path, token=token)
    if result["data"]["status"] != "RUNNING":
        break
    time.sleep(1)
assert result["data"]["status"] == "INSUFFICIENT_EVIDENCE"
assert "system_prompt" not in json.dumps(result)
assert "draft_grant" not in json.dumps(result)
request = urllib.request.Request(BASE + path + "/events", headers={"token": token})
with urllib.request.urlopen(request, timeout=15) as response:
    events = response.read().decode()
assert "event:investigation_started" in events.replace(" ", "")
assert "event:diagnosis_ready" in events.replace(" ", "")
assert call(path + "/follow-up", {"question": "Only look at recent campaigns", "scope": "last 7 days"}, token)[0] == 200
time.sleep(1)
assert call(path, token=token)[1]["data"]["scope_version"] == 1
admin = call("/api/auth/login", {"operatorId": 1, "password": "pulseflow-admin"})[1]["data"]["tokenValue"]
for method_path, body in [(path, None), (path + "/follow-up", {"question": "Growth"}),
                           (path + "/cancel", {}), (path + "/proposal", {"question": "Draft"})]:
    assert call(method_path, body, admin)[0] == 403
assert call(path + "/proposal", {"question": "Design a campaign", "promotionFacts": []}, token)[0] in (409, 503)
print("Compose smoke passed: Java ownership, PII, Agent persistence, follow-up and safe SSE")
