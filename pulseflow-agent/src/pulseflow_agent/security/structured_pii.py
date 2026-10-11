"""Small validators reused from the benchmark; no NLP engine or model loading."""

import ipaddress
import re
import unicodedata

import phonenumbers
import regex
from presidio_analyzer.predefined_recognizers import CreditCardRecognizer
from stdnum.cn import ric
from stdnum.util import clean

from pulseflow_agent.security.pii_entities import PiiEntity, overlaps

# Candidate extraction only. stdnum owns length, date, region and checksum validation.
_NUMERIC_TOKEN = re.compile(r"[0-9Xx](?:[0-9Xx]|[ \t-](?=[0-9Xx]))*")
_NETWORK_TOKEN = re.compile(r"[0-9A-Fa-f:.]+")
_CONTACT_PREFIX = regex.compile(
    r"(?<![\p{L}\p{N}_+\p{Pd}\p{Cf}])"
    r"(?:手机号(?:码)?|联系电话|电话号码?|联系号码|回拨|"
    r"contact(?:[\p{Zs}\t]+number)?|call|tel(?:ephone)?)"
    r"[\p{Zs}\t\r\n:：\p{Pd}]*\Z",
    regex.IGNORECASE,
)


def _network_spans(text: str) -> list[tuple[int, int]]:
    spans = []
    for match in _NETWORK_TOKEN.finditer(text):
        value = match.group().strip(".")
        # Covers valid/invalid dotted identifiers, without enumerating IP cases.
        dotted = "." in value.strip(":")
        try:
            ipaddress.ip_address(value)
            network = ":" in value or dotted
        except ValueError:
            network = dotted
        if network:
            spans.append(match.span())
    return spans


def _inside_identifier(text: str, start: int, end: int) -> bool:
    """Check the lexical envelope of a library candidate, not its phone syntax.

    Keep ordinary Chinese prose and spaced/plus/hyphen phone notation. ASCII
    identifiers, Unicode dash variants and invisible format separators cannot
    hide an otherwise valid numeric substring inside a business code.
    """

    def connected(char: str) -> bool:
        return (
            char.isascii()
            and (char.isalnum() or char in "_+-")
            or unicodedata.category(char) in {"Cf", "Pd"}
        )

    left, right = start, end
    while left > 0 and connected(text[left - 1]):
        left -= 1
    while right < len(text) and connected(text[right]):
        right += 1
    envelope = text[left:right]

    def spaced_identifier(boundary: int, direction: int, include_edge: bool = True) -> bool:
        cursor = boundary - 1 if direction < 0 else boundary
        edge = (
            (text[boundary:start] if direction < 0 else text[end:boundary]) if include_edge else ""
        )
        connector = any(char == "_" or unicodedata.category(char) == "Pd" for char in edge)
        while 0 <= cursor < len(text):
            char = text[cursor]
            if char in "\r\n" or not (
                char.isspace() or char == "_" or unicodedata.category(char) in {"Cf", "Pd"}
            ):
                break
            connector |= char == "_" or unicodedata.category(char) == "Pd"
            cursor += direction
        letters = False
        while 0 <= cursor < len(text):
            char = text[cursor]
            if not char.isascii() or not (char.isalnum() or char == "_"):
                break
            letters |= char.isalpha()
            cursor += direction
        return connector and letters

    # Only a complete label can protect contact notation (including Contact-).
    # A label joined to an earlier code by a spaced connector is still a code.
    label = _CONTACT_PREFIX.search(text[:start])
    if label and not spaced_identifier(label.start(), -1, include_edge=False):
        return False
    if "_" in envelope or any(c.isascii() and c.isalpha() for c in envelope):
        return True
    return spaced_identifier(left, -1) or spaced_identifier(right, 1)


class StructuredPiiDetector:
    def __init__(self) -> None:
        self._card = CreditCardRecognizer(supported_language="zh")

    def detect(self, text: str) -> list[PiiEntity]:
        found: list[PiiEntity] = []
        networks = _network_spans(text)
        for match in phonenumbers.PhoneNumberMatcher(
            text, "CN", leniency=phonenumbers.Leniency.STRICT_GROUPING
        ):
            # Dots/colons denote network/version tokens in this policy. This also
            # rejects a phone-like substring inside an invalid dotted IP or IPv6.
            if any(match.start < end and start < match.end for start, end in networks):
                continue
            if _inside_identifier(text, match.start, match.end):
                continue
            if phonenumbers.is_valid_number(match.number):
                found.append(
                    PiiEntity(
                        match.start, match.end, "PHONENUMBERS", "PHONE", 1.0, ("phonenumbers",)
                    )
                )
        for result in self._card.analyze(
            text=text,
            entities=self._card.supported_entities,
            nlp_artifacts=None,
            regex_flags=regex.ASCII | regex.IGNORECASE | regex.MULTILINE,
        ):
            if result.score > 0 and self._card.validate_result(text[result.start : result.end]):
                found.append(
                    PiiEntity(
                        result.start,
                        result.end,
                        result.entity_type,
                        "BANK_CARD",
                        float(result.score),
                        ("presidio",),
                    )
                )
        for numeric_match in _NUMERIC_TOKEN.finditer(text):
            if ric.is_valid(clean(numeric_match.group(), " -\t")):
                found.append(
                    PiiEntity(
                        numeric_match.start(),
                        numeric_match.end(),
                        "CN_RESIDENT_ID",
                        "GOVERNMENT_ID_CN",
                        1.0,
                        ("python-stdnum",),
                    )
                )
        structural = [e for e in found if e.normalized_family in {"BANK_CARD", "GOVERNMENT_ID_CN"}]
        # A card/ID must not be counted again as a phone embedded in the same span.
        found = [
            e
            for e in found
            if e.normalized_family != "PHONE" or not any(overlaps(e, other) for other in structural)
        ]
        unique = {(e.start, e.end, e.normalized_family): e for e in found}
        return sorted(unique.values(), key=lambda e: (e.start, e.end, e.normalized_family))
