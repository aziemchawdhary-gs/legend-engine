from fuzzer.composer.symbol_table import SymbolTable


def test_register_class():
    st = SymbolTable()
    st.add_class("test::model", "Person", [
        {"name": "name", "type": "String", "multiplicity": "[1]"},
        {"name": "age", "type": "Integer", "multiplicity": "[1]"},
    ])
    assert st.get_class_names() == ["test::model::Person"]
    props = st.get_class_properties("test::model::Person")
    assert len(props) == 2
    assert props[0]["name"] == "name"


def test_register_enum():
    st = SymbolTable()
    st.add_enum("test::model", "Gender", ["MALE", "FEMALE"])
    assert st.get_enum_names() == ["test::model::Gender"]
    assert st.get_enum_values("test::model::Gender") == ["MALE", "FEMALE"]


def test_register_database():
    st = SymbolTable()
    st.add_database("test::store", "TestDB")
    st.add_table("test::store::TestDB", "default", "personTable", [
        {"name": "NAME", "type": "VARCHAR(200)"},
        {"name": "AGE", "type": "INTEGER"},
    ])
    assert st.get_database_names() == ["test::store::TestDB"]
    tables = st.get_tables("test::store::TestDB")
    assert len(tables) == 1
    assert tables[0]["name"] == "personTable"


def test_pick_random_class():
    st = SymbolTable()
    st.add_class("a", "B", [])
    st.add_class("a", "C", [])
    name = st.pick_class()
    assert name in ["a::B", "a::C"]


def test_pick_from_empty_returns_none():
    st = SymbolTable()
    assert st.pick_class() is None
    assert st.pick_table("nonexistent") is None
