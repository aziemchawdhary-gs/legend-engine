"""Build probabilistic models from mined corpus sections."""
import random
import re
from collections import Counter
from typing import Dict, List

IDENTIFIER_RE = re.compile(r"\b([a-z][a-zA-Z0-9]*)\b")
TYPE_RE = re.compile(r":\s*(String|Integer|Float|Boolean|Date|DateTime|StrictDate|[A-Z][a-zA-Z0-9]*)")
MULTIPLICITY_RE = re.compile(r"\[([\d.*]+(?:\.\.\d+|\.\.\*)?)\]")
CLASS_NAME_RE = re.compile(r"Class\s+[\w:]+::(\w+)")
PROPERTY_NAME_RE = re.compile(r"^\s+(\w+)\s*:", re.MULTILINE)


class TokenModel:
    def __init__(self):
        self.identifiers: Counter = Counter()
        self.type_distribution: Counter = Counter()
        self.multiplicity_distribution: Counter = Counter()
        self.class_names: Counter = Counter()
        self.property_names: Counter = Counter()
        self.total_tokens: int = 0

    def sample_identifier(self) -> str:
        if self.identifiers:
            return _weighted_choice(self.identifiers)
        return "value"

    def sample_type(self) -> str:
        if self.type_distribution:
            return _weighted_choice(self.type_distribution)
        return "String"

    def sample_multiplicity(self) -> str:
        if self.multiplicity_distribution:
            return "[" + _weighted_choice(self.multiplicity_distribution) + "]"
        return "[1]"

    def sample_class_name(self) -> str:
        if self.class_names:
            return _weighted_choice(self.class_names)
        return "Entity"

    def sample_property_name(self) -> str:
        if self.property_names:
            return _weighted_choice(self.property_names)
        return "value"

    def to_dict(self) -> Dict:
        return {
            "total_tokens": self.total_tokens,
            "identifiers": dict(self.identifiers.most_common(200)),
            "type_distribution": dict(self.type_distribution),
            "multiplicity_distribution": dict(self.multiplicity_distribution),
            "class_names": dict(self.class_names.most_common(100)),
            "property_names": dict(self.property_names.most_common(200)),
        }


def _weighted_choice(counter: Counter) -> str:
    items = list(counter.keys())
    weights = list(counter.values())
    return random.choices(items, weights=weights, k=1)[0]


def build_token_model(sections: List[Dict], section_type: str) -> TokenModel:
    model = TokenModel()

    for section in sections:
        if section.get("type") != section_type:
            continue
        code = section.get("code", "")

        for m in IDENTIFIER_RE.finditer(code):
            model.identifiers[m.group(1)] += 1
            model.total_tokens += 1

        for m in TYPE_RE.finditer(code):
            model.type_distribution[m.group(1)] += 1

        for m in MULTIPLICITY_RE.finditer(code):
            model.multiplicity_distribution[m.group(1)] += 1

        for m in CLASS_NAME_RE.finditer(code):
            model.class_names[m.group(1)] += 1

        for m in PROPERTY_NAME_RE.finditer(code):
            model.property_names[m.group(1)] += 1

    return model
