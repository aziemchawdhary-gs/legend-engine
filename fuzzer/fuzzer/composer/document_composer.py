"""Orchestrates generation of multi-section Pure documents with consistent cross-references."""
import random
import string
from typing import List, Optional

from .symbol_table import SymbolTable

PURE_PRIMITIVE_TYPES = ["String", "Integer", "Float", "Boolean", "Date", "DateTime", "StrictDate"]

PURE_TO_SQL_TYPE = {
    "String": "VARCHAR(200)",
    "Integer": "INTEGER",
    "Float": "DOUBLE",
    "Boolean": "BIT",
    "Date": "DATE",
    "DateTime": "TIMESTAMP",
    "StrictDate": "DATE",
}

MULTIPLICITIES = ["[1]", "[0..1]", "[*]", "[1..*]"]


def _random_name(prefix: str = "", length: int = 6) -> str:
    suffix = "".join(random.choices(string.ascii_lowercase, k=length))
    return prefix + suffix.capitalize()


def _random_package(depth: int = 2) -> str:
    parts = [_random_name(length=4) for _ in range(depth)]
    return "::".join(parts)


class DocumentComposer:
    def __init__(self, seed: Optional[int] = None):
        self.symbol_table = SymbolTable()
        if seed is not None:
            random.seed(seed)
        self._package = _random_package()

    def generate_pure_section(self, num_classes: int = 2, num_enums: int = 1) -> str:
        lines = ["###Pure"]

        for _ in range(num_enums):
            enum_name = _random_name("Enum")
            num_values = random.randint(2, 5)
            values = [_random_name("VAL").upper() for _ in range(num_values)]
            self.symbol_table.add_enum(self._package, enum_name, values)
            lines.append(f"Enum {self._package}::{enum_name}")
            lines.append("{")
            lines.append("  " + ", ".join(values))
            lines.append("}")
            lines.append("")

        for _ in range(num_classes):
            class_name = _random_name("Cls")
            num_props = random.randint(1, 5)
            properties = []
            prop_lines = []
            for _ in range(num_props):
                prop_name = _random_name(length=5)
                prop_type = random.choice(PURE_PRIMITIVE_TYPES)
                multiplicity = random.choice(MULTIPLICITIES)
                properties.append({
                    "name": prop_name,
                    "type": prop_type,
                    "multiplicity": multiplicity,
                })
                prop_lines.append(f"  {prop_name}: {prop_type}{multiplicity};")

            self.symbol_table.add_class(self._package, class_name, properties)
            lines.append(f"Class {self._package}::{class_name}")
            lines.append("{")
            lines.extend(prop_lines)
            lines.append("}")
            lines.append("")

        return "\n".join(lines)

    def generate_relational_section(self, num_databases: int = 1) -> str:
        lines = ["###Relational"]

        for _ in range(num_databases):
            db_name = _random_name("DB")
            db_package = self._package
            db_fqn = f"{db_package}::{db_name}"
            self.symbol_table.add_database(db_package, db_name)

            table_lines = []
            for class_fqn in self.symbol_table.get_class_names():
                props = self.symbol_table.get_class_properties(class_fqn)
                class_short = class_fqn.split("::")[-1]
                table_name = class_short.lower() + "Table"

                columns = []
                col_defs = []
                for prop in props:
                    col_name = prop["name"].upper()
                    col_type = PURE_TO_SQL_TYPE.get(prop["type"], "VARCHAR(200)")
                    columns.append({"name": col_name, "type": col_type})
                    col_defs.append(f"    {col_name} {col_type}")

                self.symbol_table.add_table(db_fqn, "default", table_name, columns)
                table_lines.append(f"  Table {table_name}")
                table_lines.append("  (")
                table_lines.append(",\n".join(col_defs))
                table_lines.append("  )")

            lines.append(f"Database {db_fqn}")
            lines.append("(")
            lines.extend(table_lines)
            lines.append(")")
            lines.append("")

        return "\n".join(lines)

    def generate_m2m_mapping_section(self) -> str:
        lines = ["###Mapping"]

        mapping_name = _random_name("Map")
        self.symbol_table.add_mapping(self._package, mapping_name)

        class_names = self.symbol_table.get_class_names()
        if len(class_names) < 2:
            lines.append(f"Mapping {self._package}::{mapping_name}")
            lines.append("(")
            lines.append(")")
            return "\n".join(lines)

        target_class = class_names[0]
        source_class = class_names[1]
        target_props = self.symbol_table.get_class_properties(target_class)
        source_props = self.symbol_table.get_class_properties(source_class)

        lines.append(f"Mapping {self._package}::{mapping_name}")
        lines.append("(")
        lines.append(f"  *{target_class}: Pure")
        lines.append("  {")
        lines.append(f"    ~src {source_class}")

        for tp in target_props:
            matching = [sp for sp in source_props if sp["type"] == tp["type"]]
            if matching:
                src_prop = random.choice(matching)
                lines.append(f"    {tp['name']}: $src.{src_prop['name']},")
            else:
                if tp["type"] == "String":
                    lines.append(f"    {tp['name']}: 'default',")
                elif tp["type"] == "Integer":
                    lines.append(f"    {tp['name']}: 0,")
                elif tp["type"] == "Float":
                    lines.append(f"    {tp['name']}: 0.0,")
                elif tp["type"] == "Boolean":
                    lines.append(f"    {tp['name']}: true,")
                else:
                    lines.append(f"    {tp['name']}: $src.{source_props[0]['name'] if source_props else 'id'},")

        if lines[-1].endswith(","):
            lines[-1] = lines[-1][:-1]

        lines.append("  }")
        lines.append(")")
        return "\n".join(lines)

    def generate_relational_mapping_section(self) -> str:
        lines = ["###Mapping"]

        mapping_name = _random_name("Map")
        self.symbol_table.add_mapping(self._package, mapping_name)

        db_names = self.symbol_table.get_database_names()
        class_names = self.symbol_table.get_class_names()

        lines.append(f"Mapping {self._package}::{mapping_name}")
        lines.append("(")

        if db_names and class_names:
            db_fqn = db_names[0]
            for class_fqn in class_names:
                tables = self.symbol_table.get_tables(db_fqn)
                class_short = class_fqn.split("::")[-1]
                matching_tables = [t for t in tables if class_short.lower() in t["name"].lower()]
                if not matching_tables:
                    continue
                table = matching_tables[0]
                props = self.symbol_table.get_class_properties(class_fqn)

                lines.append(f"  {class_fqn}: Relational")
                lines.append("  {")
                lines.append(f"    ~primaryKey")
                lines.append(f"    (")
                if table["columns"]:
                    lines.append(f"      [{db_fqn}]{table['name']}.{table['columns'][0]['name']}")
                lines.append(f"    )")
                lines.append(f"    ~mainTable [{db_fqn}]{table['name']}")

                for prop in props:
                    col_name = prop["name"].upper()
                    matching_cols = [c for c in table["columns"] if c["name"] == col_name]
                    if matching_cols:
                        lines.append(f"    {prop['name']}: [{db_fqn}]{table['name']}.{col_name},")

                if lines[-1].endswith(","):
                    lines[-1] = lines[-1][:-1]

                lines.append("  }")

        lines.append(")")
        return "\n".join(lines)

    def generate_full_document(
        self,
        num_classes: int = 2,
        num_enums: int = 1,
        num_databases: int = 1,
        mapping_type: str = "m2m",
    ) -> str:
        sections = []

        sections.append(self.generate_pure_section(num_classes=num_classes, num_enums=num_enums))

        if mapping_type == "relational" or num_databases > 0:
            sections.append(self.generate_relational_section(num_databases=num_databases))

        if mapping_type == "m2m":
            sections.append(self.generate_m2m_mapping_section())
        elif mapping_type == "relational":
            sections.append(self.generate_relational_mapping_section())

        return "\n\n".join(sections)
