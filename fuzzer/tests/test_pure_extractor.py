from fuzzer.miner.pure_extractor import extract_sections, extract_sections_from_directory
import tempfile
import os


def test_extract_sections_single_section():
    code = "###Pure\nClass test::Person { name: String[1]; }\n"
    sections = extract_sections(code)
    assert len(sections) == 1
    assert sections[0]["type"] == "Pure"
    assert "Class test::Person" in sections[0]["code"]


def test_extract_sections_multiple_sections():
    code = (
        "###Pure\n"
        "Class test::Person { name: String[1]; }\n"
        "\n"
        "###Relational\n"
        "Database test::DB ( Table personTable (name VARCHAR(200)) )\n"
        "\n"
        "###Mapping\n"
        "Mapping test::MyMapping ( )\n"
    )
    sections = extract_sections(code)
    assert len(sections) == 3
    types = [s["type"] for s in sections]
    assert types == ["Pure", "Relational", "Mapping"]


def test_extract_sections_no_header():
    code = "Class test::Person { name: String[1]; }\n"
    sections = extract_sections(code)
    assert len(sections) == 1
    assert sections[0]["type"] == "Pure"


def test_extract_sections_from_directory():
    with tempfile.TemporaryDirectory() as tmpdir:
        with open(os.path.join(tmpdir, "test1.pure"), "w") as f:
            f.write("###Pure\nClass a::B { x: String[1]; }\n")
        with open(os.path.join(tmpdir, "test2.pure"), "w") as f:
            f.write("###Relational\nDatabase a::DB ( )\n")

        sections = extract_sections_from_directory(tmpdir)
        assert len(sections) == 2
        types = {s["type"] for s in sections}
        assert types == {"Pure", "Relational"}
