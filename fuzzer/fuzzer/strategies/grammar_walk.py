"""Grammar walk fuzzing strategy: generates random DSL code via the document composer + mutations."""
import random
import re
from typing import List, Optional

from ..composer.document_composer import DocumentComposer

TOKEN_RE = re.compile(r"(\s+|[{}()\[\];:,.<>*=+\-/|@#~!]+|[^\s{}()\[\];:,.<>*=+\-/|@#~!]+)")


def _tokenize(code: str) -> List[str]:
    return TOKEN_RE.findall(code)


def _detokenize(tokens: List[str]) -> str:
    return "".join(tokens)


class GrammarWalkStrategy:
    def __init__(self, seed: Optional[int] = None):
        self._seed = seed
        if seed is not None:
            random.seed(seed)

    def generate(self, section_type: str = "full_m2m") -> str:
        composer = DocumentComposer()

        if section_type == "pure":
            return composer.generate_pure_section(
                num_classes=random.randint(1, 4),
                num_enums=random.randint(0, 2),
            )
        elif section_type == "relational":
            composer.generate_pure_section(num_classes=random.randint(1, 3))
            return composer.generate_relational_section(num_databases=1)
        elif section_type == "full_m2m":
            return composer.generate_full_document(
                num_classes=random.randint(2, 4),
                num_enums=random.randint(0, 2),
                num_databases=0,
                mapping_type="m2m",
            )
        elif section_type == "full_relational":
            return composer.generate_full_document(
                num_classes=random.randint(1, 3),
                num_enums=random.randint(0, 1),
                num_databases=1,
                mapping_type="relational",
            )
        else:
            raise ValueError(f"Unknown section_type: {section_type}")

    def mutate(self, code: str, mutation_type: str = "delete_token") -> str:
        tokens = _tokenize(code)
        non_ws_indices = [i for i, t in enumerate(tokens) if t.strip()]

        if not non_ws_indices:
            return code

        if mutation_type == "delete_token":
            idx = random.choice(non_ws_indices)
            tokens.pop(idx)

        elif mutation_type == "swap_tokens":
            if len(non_ws_indices) >= 2:
                i, j = random.sample(non_ws_indices, 2)
                tokens[i], tokens[j] = tokens[j], tokens[i]

        elif mutation_type == "duplicate_token":
            idx = random.choice(non_ws_indices)
            tokens.insert(idx, tokens[idx])

        elif mutation_type == "inject_garbage":
            idx = random.choice(non_ws_indices)
            garbage = "".join(random.choices("@#$%^&!?<>", k=random.randint(1, 5)))
            tokens.insert(idx, garbage)

        return _detokenize(tokens)

    def generate_batch(self, count: int, section_type: str = "full_m2m") -> List[str]:
        return [self.generate(section_type=section_type) for _ in range(count)]
