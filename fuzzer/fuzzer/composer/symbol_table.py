"""Shared symbol table for coordinated multi-section document generation."""
import random
from typing import List, Dict, Optional


class SymbolTable:
    """Tracks all generated names across sections for referential consistency."""

    def __init__(self):
        self._classes: Dict[str, Dict] = {}
        self._enums: Dict[str, Dict] = {}
        self._associations: Dict[str, Dict] = {}
        self._databases: Dict[str, Dict] = {}
        self._tables: Dict[str, List[Dict]] = {}
        self._mappings: Dict[str, Dict] = {}

    def add_class(self, package: str, name: str, properties: List[Dict]):
        fqn = f"{package}::{name}"
        self._classes[fqn] = {"package": package, "name": name, "properties": properties}

    def get_class_names(self) -> List[str]:
        return sorted(self._classes.keys())

    def get_class_properties(self, fqn: str) -> List[Dict]:
        cls = self._classes.get(fqn)
        return cls["properties"] if cls else []

    def pick_class(self) -> Optional[str]:
        names = list(self._classes.keys())
        return random.choice(names) if names else None

    def add_enum(self, package: str, name: str, values: List[str]):
        fqn = f"{package}::{name}"
        self._enums[fqn] = {"package": package, "name": name, "values": values}

    def get_enum_names(self) -> List[str]:
        return sorted(self._enums.keys())

    def get_enum_values(self, fqn: str) -> List[str]:
        enum = self._enums.get(fqn)
        return enum["values"] if enum else []

    def pick_enum(self) -> Optional[str]:
        names = list(self._enums.keys())
        return random.choice(names) if names else None

    def add_database(self, package: str, name: str):
        fqn = f"{package}::{name}"
        self._databases[fqn] = {"package": package, "name": name}
        self._tables.setdefault(fqn, [])

    def get_database_names(self) -> List[str]:
        return sorted(self._databases.keys())

    def add_table(self, db_fqn: str, schema: str, name: str, columns: List[Dict]):
        self._tables.setdefault(db_fqn, []).append({
            "schema": schema,
            "name": name,
            "columns": columns,
        })

    def get_tables(self, db_fqn: str) -> List[Dict]:
        return self._tables.get(db_fqn, [])

    def pick_table(self, db_fqn: str) -> Optional[Dict]:
        tables = self._tables.get(db_fqn, [])
        return random.choice(tables) if tables else None

    def pick_database(self) -> Optional[str]:
        names = list(self._databases.keys())
        return random.choice(names) if names else None

    def add_mapping(self, package: str, name: str):
        fqn = f"{package}::{name}"
        self._mappings[fqn] = {"package": package, "name": name}

    def get_mapping_names(self) -> List[str]:
        return sorted(self._mappings.keys())
