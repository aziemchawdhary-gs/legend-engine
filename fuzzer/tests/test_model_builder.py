from fuzzer.miner.model_builder import build_token_model, TokenModel


def test_build_token_model_from_sections():
    sections = [
        {"type": "Pure", "code": "Class test::Person { name: String[1]; age: Integer[1]; }", "source": "test"},
        {"type": "Pure", "code": "Class test::Firm { legalName: String[1]; }", "source": "test"},
    ]
    model = build_token_model(sections, section_type="Pure")
    assert model.total_tokens > 0
    ident = model.sample_identifier()
    assert isinstance(ident, str)
    assert len(ident) > 0


def test_build_token_model_type_distribution():
    sections = [
        {"type": "Pure", "code": "Class a::B { x: String[1]; y: String[1]; z: Integer[1]; }", "source": "test"},
    ]
    model = build_token_model(sections, section_type="Pure")
    dist = model.type_distribution
    assert dist.get("String", 0) >= dist.get("Integer", 0)


def test_token_model_multiplicity_distribution():
    sections = [
        {"type": "Pure", "code": "Class a::B { x: String[1]; y: String[0..1]; z: String[*]; }", "source": "test"},
    ]
    model = build_token_model(sections, section_type="Pure")
    mult = model.sample_multiplicity()
    assert mult in ["[1]", "[0..1]", "[*]", "[1..*]"]


def test_empty_sections():
    model = build_token_model([], section_type="Pure")
    assert model.total_tokens == 0
    ident = model.sample_identifier()
    assert isinstance(ident, str)
