"""Spring's /internal/agent/* API in memory, same contracts, so evals run without Spring or a database.

The precision rules mirror backend/.../BookingDraftResolver.java closely
enough for the eval scenarios (exact names, hoy/mañana/weekday/dd-mm/ISO
dates, exact or approximate times, franjas as blockers).
ponytail: a re-implementation, not the real thing - if the Java resolver
changes, update this or point the evals at a running backend.
"""

import json
import re
import unicodedata
import uuid
from dataclasses import dataclass, field
from datetime import date, datetime, time, timedelta
from typing import Any

import httpx

WEEKDAYS = {"lunes": 0, "martes": 1, "miercoles": 2, "jueves": 3, "viernes": 4, "sabado": 5, "domingo": 6}
LABEL_DAYS = ["lun", "mar", "mié", "jue", "vie", "sáb", "dom"]
ANY = {"cualquiera", "cualquier", "indistinto", "el que sea", "da igual"}
RANGE_WORDS = re.compile(r"manana|tarde|noche|mediodia|despues|antes|entre|desde|hasta|temprano")


def norm(text: str | None) -> str:
    if not text:
        return ""
    s = unicodedata.normalize("NFD", text)
    s = "".join(c for c in s if unicodedata.category(c) != "Mn").lower()
    return re.sub(r"\s+", " ", re.sub(r"[^a-z0-9:./+ -]", " ", s)).strip()


def label(dt: datetime) -> str:
    return f"{LABEL_DAYS[dt.weekday()]} {dt:%d/%m %H:%M}"


@dataclass
class State:
    now: datetime
    services: list[dict[str, Any]]
    professionals: list[dict[str, Any]]
    hours: tuple[int, int]
    open_weekdays: set[int]
    booked: list[dict[str, Any]]
    customers: list[dict[str, Any]]
    business: dict[str, Any]
    customer_id: str | None = None
    drafts: dict[str, dict[str, Any]] = field(default_factory=dict)
    user_turn: int = 0
    # What the graders read.
    created: list[dict[str, Any]] = field(default_factory=list)
    cancelled: list[str] = field(default_factory=list)
    rescheduled: list[dict[str, Any]] = field(default_factory=list)
    handoffs: list[str] = field(default_factory=list)


class FakeCore:
    def __init__(self, fixture: dict[str, Any], now: datetime) -> None:
        self.s = State(
            now=now,
            services=fixture["services"],
            professionals=fixture["professionals"],
            hours=(fixture["hours"][0], fixture["hours"][1]),
            open_weekdays=set(fixture["open_weekdays"]),
            booked=[dict(b, start=datetime.fromisoformat(b["start"])) for b in fixture.get("booked", [])],
            customers=fixture.get("customers", []),
            business=fixture["business"],
        )
        self.calls: list[str] = []

    # ---- transport ------------------------------------------------------------

    def transport(self) -> httpx.MockTransport:
        return httpx.MockTransport(self.handle)

    def handle(self, request: httpx.Request) -> httpx.Response:
        path = request.url.path.removeprefix("/internal/agent")
        body = json.loads(request.content) if request.content else {}
        self.calls.append(path)
        try:
            result = self.route(request.method, path, body, dict(request.url.params))
        except HttpError as e:
            return httpx.Response(e.status, json={"detail": e.detail})
        return httpx.Response(200, json=result)

    def route(self, method: str, path: str, body: dict[str, Any], params: dict[str, str]) -> Any:
        if path == "/business-info":
            return self.s.business
        if path == "/services":
            return self.s.services
        if path == "/professionals":
            return [{"id": p["id"], "name": p["name"]} for p in self.pros_for(params["serviceId"])]
        if path == "/availability/search":
            return self.search(body)
        if path == "/customer/identify":
            return self.identify(body)
        if path == "/customer/appointments":
            return self.my_appointments()
        if path == "/customer/profile":
            return self.profile()
        if path == "/bookings/prepare":
            return self.prepare_booking(body)
        if path == "/cancellations/prepare":
            return self.prepare_cancellation(body)
        if path == "/reschedules/prepare":
            return self.prepare_reschedule(body)
        if m := re.fullmatch(r"/(bookings|cancellations|reschedules)/([^/]+)/commit", path):
            return self.commit(m.group(1), m.group(2))
        if path == "/handoff":
            self.s.handoffs.append(body.get("reason", ""))
            return {"handedOff": True}
        raise HttpError(404, "not found")

    # ---- the harness drives these between turns --------------------------------

    def new_user_turn(self) -> None:
        self.s.user_turn += 1

    def pending(self) -> dict[str, Any] | None:
        open_ = [d for d in self.s.drafts.values() if d["status"] == "PENDING"]
        if not open_:
            return None
        d = open_[-1]
        return {"draftId": d["id"], "type": d["type"], "summary": d["summary"], "mode": d["mode"]}

    # ---- domain ------------------------------------------------------------------

    def pros_for(self, service_id: str) -> list[dict[str, Any]]:
        return [p for p in self.s.professionals if service_id in p["services"]]

    def service(self, service_id: str) -> dict[str, Any]:
        return next(s for s in self.s.services if s["id"] == service_id)

    def free_slots(self, pro: dict[str, Any], service_id: str, day: date) -> list[datetime]:
        if day.weekday() not in self.s.open_weekdays:
            return []
        duration = timedelta(minutes=self.service(service_id)["durationMinutes"])
        tz = self.s.now.tzinfo
        start = datetime.combine(day, time(self.s.hours[0]), tz)
        close = datetime.combine(day, time(self.s.hours[1]), tz)
        slots = []
        while start + duration <= close:
            busy = any(
                b["professionalId"] == pro["id"]
                and b["start"] < start + duration
                and start < b["start"] + timedelta(minutes=b.get("durationMinutes", 30))
                for b in self.s.booked
                if b.get("status", "CONFIRMED") == "CONFIRMED"
            )
            if not busy and start > self.s.now:
                slots.append(start)
            start += timedelta(minutes=30)
        return slots

    def search(self, body: dict[str, Any]) -> dict[str, Any]:
        today = self.s.now.date()
        start = max(date.fromisoformat(body["dateFrom"]), today) if body.get("dateFrom") else today
        end = date.fromisoformat(body["dateTo"]) if body.get("dateTo") else start
        end = min(end, start + timedelta(days=13))
        pros = [
            p
            for p in self.pros_for(body["serviceId"])
            if not body.get("professionalId") or p["id"] == body["professionalId"]
        ]
        t_from = time.fromisoformat(body["timeFrom"]) if body.get("timeFrom") else None
        t_to = time.fromisoformat(body["timeTo"]) if body.get("timeTo") else None
        slots: list[dict[str, Any]] = []
        d = start
        while d <= end and len(slots) < 15:
            day = []
            for p in pros:
                for s in self.free_slots(p, body["serviceId"], d):
                    if (t_from is None or s.time() >= t_from) and (t_to is None or s.time() <= t_to):
                        day.append(
                            {
                                "professionalId": p["id"],
                                "professional": p["name"],
                                "start": s.isoformat(),
                                "label": label(s),
                            }
                        )
            slots += sorted(day, key=lambda x: x["start"])
            d += timedelta(days=1)
        return {"slots": slots[:15], "searchedUntil": end.isoformat()}

    def identify(self, body: dict[str, Any]) -> dict[str, Any]:
        digits = re.sub(r"\D", "", body.get("phone", ""))
        if not 8 <= len(digits) <= 15:
            return {"error": "invalid_phone"}
        customer = next((c for c in self.s.customers if re.sub(r"\D", "", c["phone"]) == digits), None)
        known = customer is not None
        if customer is None:
            customer = {"id": f"c-{uuid.uuid4().hex[:6]}", "name": body.get("name") or "Cliente web", "phone": digits}
            self.s.customers.append(customer)
        self.s.customer_id = customer["id"]
        return {"name": customer["name"], "known": known}

    def require_customer(self) -> dict[str, Any] | None:
        if self.s.customer_id is None:
            return {"error": "customer_not_identified", "hint": "Pedí nombre y teléfono y llamá a identify_customer."}
        return None

    def my_appointments(self) -> dict[str, Any]:
        if err := self.require_customer():
            return err
        mine = [
            b
            for b in self.s.booked
            if b.get("customerId") == self.s.customer_id
            and b.get("status", "CONFIRMED") == "CONFIRMED"
            and b["start"] > self.s.now
        ]
        return {
            "appointments": [
                {
                    "id": b["id"],
                    "service": self.service(b["serviceId"])["name"],
                    "professional": self.pro(b["professionalId"])["name"],
                    "start": b["start"].isoformat(),
                    "label": label(b["start"]),
                }
                for b in sorted(mine, key=lambda b: b["start"])
            ]
        }

    def profile(self) -> dict[str, Any]:
        if err := self.require_customer():
            return err
        c = next(c for c in self.s.customers if c["id"] == self.s.customer_id)
        return {
            "name": c["name"],
            "visits": c.get("visits", 0),
            "lastService": c.get("lastService"),
            "usualProfessional": c.get("usualProfessional"),
        }

    def pro(self, pro_id: str) -> dict[str, Any]:
        return next(p for p in self.s.professionals if p["id"] == pro_id)

    # ---- the precision rules (BookingDraftResolver) ------------------------------

    def resolve(self, body: dict[str, Any], services: list[dict[str, Any]]) -> dict[str, Any]:
        amb: list[str] = []
        blockers: list[str] = []
        candidates: list[str] = []
        t = norm(body.get("serviceText"))
        matches = [s for s in services if norm(s["name"]) == t] or [
            s for s in services if t and (t in norm(s["name"]) or norm(s["name"]) in t)
        ]
        service = matches[0] if len(matches) == 1 else None
        if service is None:
            blockers.append("service_ambiguous" if matches else "service_not_found")
            candidates += [s["name"] for s in (matches or services)]
        pro, any_pro = None, False
        if service:
            pros = self.pros_for(service["id"])
            p = norm(body.get("professionalText"))
            if not p:
                if len(pros) == 1:
                    pro = pros[0]
                else:
                    any_pro = True
                    amb.append("professional_not_specified")
            elif p in ANY:
                any_pro = True
            else:
                pm = [
                    x
                    for x in pros
                    if norm(x["name"]) == p or norm(x["name"]).split(" ")[0] == p or p in norm(x["name"])
                ]
                if len(pm) == 1:
                    pro = pm[0]
                else:
                    blockers.append("professional_ambiguous" if pm else "professional_not_found")
                    candidates += [x["name"] for x in (pm or pros)]
        day = self.resolve_date(norm(body.get("dateText")), amb, blockers)
        at = self.resolve_time(norm(body.get("timeText")), amb, blockers)
        return {
            "service": service,
            "pro": pro,
            "any": any_pro,
            "date": day,
            "time": at,
            "amb": amb,
            "blockers": blockers,
            "candidates": candidates,
        }

    def resolve_date(self, d: str, amb: list[str], blockers: list[str]) -> date | None:
        today = self.s.now.date()
        if not d:
            blockers.append("date_missing")
            return None
        if d == "hoy":
            return today
        if d == "manana":
            return today + timedelta(days=1)
        if d == "pasado manana":
            return today + timedelta(days=2)
        try:
            return date.fromisoformat(d)
        except ValueError:
            pass
        if m := re.fullmatch(r"(\d{1,2})[/-](\d{1,2})", d):
            day = date(today.year, int(m.group(2)), int(m.group(1)))
            return day if day >= today else day.replace(year=today.year + 1)
        if (m := re.fullmatch(r"(?:el )?([a-z]+)(?: (\d{1,2}))?", d)) and m.group(1) in WEEKDAYS:
            day = today
            while day.weekday() != WEEKDAYS[m.group(1)]:
                day += timedelta(days=1)
            if m.group(2):
                for i in range(3):
                    if (day + timedelta(weeks=i)).day == int(m.group(2)):
                        return day + timedelta(weeks=i)
                blockers.append("date_weekday_mismatch")
                return None
            if day == today:
                amb.append("date_same_weekday")
            return day
        blockers.append("date_unclear")
        return None

    def resolve_time(self, t: str, amb: list[str], blockers: list[str]) -> time | None:
        if not t:
            blockers.append("time_missing")
            return None
        approx = False
        if m := re.fullmatch(r"(?:tipo|como a las|como|a eso de las|cerca de las|mas o menos a las)\s+(.+)", t):
            approx, t = True, m.group(1)
        m = re.fullmatch(r"(?:a las )?(\d{1,2})(?:[:.](\d{2}))?\s*(?:hs|h|horas)?", t)
        if not m:
            blockers.append("time_range" if RANGE_WORDS.search(t) else "time_unclear")
            return None
        h, mi = int(m.group(1)), int(m.group(2) or 0)
        if 1 <= h <= 7:
            h += 12
            amb.append("time_am_pm")
        if approx:
            amb.append("time_approximate")
        return time(h, mi)

    def mode(self, amb: list[str]) -> str:
        cm = self.s.business.get("confirmationMode", "STANDARD")
        if cm == "ALWAYS":
            return "CONFIRM"
        if cm == "DATE_TIME_ONLY":
            return "CONFIRM" if {"date_same_weekday", "time_approximate", "time_am_pm"} & set(amb) else "AUTO"
        return "CONFIRM" if amb else "AUTO"

    def slot_for(self, r: dict[str, Any], service_id: str) -> tuple[dict[str, Any] | None, datetime, list[str]]:
        start = datetime.combine(r["date"], r["time"], self.s.now.tzinfo)
        pros = [p for p in self.pros_for(service_id) if r["any"] or p is r["pro"]]
        chosen = next((p for p in pros if start in self.free_slots(p, service_id, r["date"])), None)
        alternatives: list[str] = []
        if chosen is None:
            for i in range(7):
                for p in pros:
                    alternatives += [
                        f"{label(s)} con {p['name']}"
                        for s in self.free_slots(p, service_id, r["date"] + timedelta(days=i))
                    ]
                if len(alternatives) >= 3:
                    break
        return chosen, start, sorted(alternatives)[:3]

    def prepare_booking(self, body: dict[str, Any]) -> dict[str, Any]:
        if err := self.require_customer():
            return err
        r = self.resolve(body, self.s.services)
        if r["blockers"]:
            return {"error": "needs_clarification", "blockers": r["blockers"], "candidates": r["candidates"]}
        chosen, start, alternatives = self.slot_for(r, r["service"]["id"])
        if start <= self.s.now:
            return {"error": "time_in_past"}
        mine = next(
            (
                b
                for b in self.s.booked
                if b.get("customerId") == self.s.customer_id
                and b["start"] == start
                and b.get("status", "CONFIRMED") == "CONFIRMED"
            ),
            None,
        )
        if mine is not None:
            service, pro = self.service(mine["serviceId"])["name"], self.pro(mine["professionalId"])["name"]
            summary = f"{service} con {pro}, {label(start)}"
            return {"error": "already_booked", "appointmentId": mine["id"], "summary": summary}
        if chosen is None:
            return {"error": "slot_unavailable", "alternatives": alternatives}
        amb = list(r["amb"])
        if any(b.get("customerId") == self.s.customer_id and b["start"].date() == r["date"] for b in self.s.booked):
            amb.append("possible_duplicate")
        price = r["service"]["price"]
        summary = f"{r['service']['name']} con {chosen['name']}, {label(start)}, ${price}"
        return self.draft(
            "BOOKING",
            summary,
            self.mode(amb),
            amb,
            {"serviceId": r["service"]["id"], "professionalId": chosen["id"], "start": start},
        )

    def prepare_cancellation(self, body: dict[str, Any]) -> dict[str, Any]:
        if err := self.require_customer():
            return err
        appt = self.own(body.get("appointmentId"))
        if appt is None:
            return {"error": "appointment_not_found"}
        service, pro = self.service(appt["serviceId"])["name"], self.pro(appt["professionalId"])["name"]
        summary = f"Cancelar {service} con {pro}, {label(appt['start'])}"
        return self.draft("CANCELLATION", summary, "CONFIRM", [], {"appointmentId": appt["id"]})

    def prepare_reschedule(self, body: dict[str, Any]) -> dict[str, Any]:
        if err := self.require_customer():
            return err
        appt = self.own(body.get("appointmentId"))
        if appt is None:
            return {"error": "appointment_not_found"}
        service = self.service(appt["serviceId"])
        r = self.resolve(
            {
                **body,
                "serviceText": service["name"],
                "professionalText": body.get("professionalText") or self.pro(appt["professionalId"])["name"],
            },
            [service],
        )
        if r["blockers"]:
            return {"error": "needs_clarification", "blockers": r["blockers"], "candidates": r["candidates"]}
        chosen, start, alternatives = self.slot_for(r, service["id"])
        if chosen is None:
            return {"error": "slot_unavailable", "alternatives": alternatives}
        summary = f"Pasar {service['name']} del {label(appt['start'])} al {label(start)} con {chosen['name']}"
        return self.draft(
            "RESCHEDULE",
            summary,
            self.mode(r["amb"]),
            r["amb"],
            {"appointmentId": appt["id"], "professionalId": chosen["id"], "start": start},
        )

    def own(self, appointment_id: str | None) -> dict[str, Any] | None:
        return next(
            (
                b
                for b in self.s.booked
                if b["id"] == appointment_id
                and b.get("customerId") == self.s.customer_id
                and b.get("status", "CONFIRMED") == "CONFIRMED"
            ),
            None,
        )

    def draft(self, type_: str, summary: str, mode: str, amb: list[str], data: dict[str, Any]) -> dict[str, Any]:
        draft_id = f"d-{uuid.uuid4().hex[:8]}"
        self.s.drafts[draft_id] = {
            "id": draft_id,
            "type": type_,
            "summary": summary,
            "mode": mode,
            "status": "PENDING",
            "turn": self.s.user_turn,
            **data,
        }
        return {"draftId": draft_id, "summary": summary, "mode": mode, "ambiguities": amb}

    def commit(self, kind: str, draft_id: str) -> dict[str, Any]:
        expected = {"bookings": "BOOKING", "cancellations": "CANCELLATION", "reschedules": "RESCHEDULE"}[kind]
        d = self.s.drafts.get(draft_id)
        if d is None or d["type"] != expected:
            raise HttpError(404, "Draft not found")
        if d["status"] != "PENDING":
            raise HttpError(410, "Draft expired or already used: prepare it again")
        if d["mode"] == "CONFIRM" and self.s.user_turn <= d["turn"]:
            raise HttpError(409, "This draft needs the customer's confirmation first: show the summary and ask")
        d["status"] = "COMMITTED"
        if expected == "BOOKING":
            appt = {
                "id": f"a-{uuid.uuid4().hex[:6]}",
                "serviceId": d["serviceId"],
                "professionalId": d["professionalId"],
                "start": d["start"],
                "customerId": self.s.customer_id,
                "durationMinutes": self.service(d["serviceId"])["durationMinutes"],
            }
            self.s.booked.append(appt)
            self.s.created.append(
                {
                    "service": self.service(d["serviceId"])["name"],
                    "professional": self.pro(d["professionalId"])["name"],
                    "start": d["start"].strftime("%Y-%m-%dT%H:%M"),
                }
            )
            return {"appointmentId": appt["id"], "summary": d["summary"]}
        target = self.own(d["appointmentId"])
        if target is None:
            raise HttpError(404, "Appointment not found")
        appt = target
        appt["status"] = "CANCELLED"
        if expected == "CANCELLATION":
            self.s.cancelled.append(appt["id"])
            return {"cancelled": True, "summary": d["summary"]}
        new = {
            **appt,
            "id": f"a-{uuid.uuid4().hex[:6]}",
            "status": "CONFIRMED",
            "start": d["start"],
            "professionalId": d["professionalId"],
        }
        self.s.booked.append(new)
        self.s.rescheduled.append({"from": appt["id"], "start": d["start"].strftime("%Y-%m-%dT%H:%M")})
        return {"appointmentId": new["id"], "summary": d["summary"]}


class HttpError(Exception):
    def __init__(self, status: int, detail: str) -> None:
        self.status = status
        self.detail = detail
