from fuzzer.strategies.grammar_walk import GrammarWalkStrategy


def test_generate_pure_section():
    strategy = GrammarWalkStrategy(seed=42)
    code = strategy.generate(section_type="pure")
    assert "###Pure" in code
    assert "Class" in code


def test_generate_relational_section():
    strategy = GrammarWalkStrategy(seed=42)
    code = strategy.generate(section_type="relational")
    assert "###Relational" in code
    assert "Database" in code


def test_generate_full_document():
    strategy = GrammarWalkStrategy(seed=42)
    code = strategy.generate(section_type="full_m2m")
    assert "###Pure" in code
    assert "###Mapping" in code


def test_generate_full_relational_document():
    strategy = GrammarWalkStrategy(seed=42)
    code = strategy.generate(section_type="full_relational")
    assert "###Pure" in code
    assert "###Relational" in code
    assert "###Mapping" in code


def test_mutate_delete_token():
    strategy = GrammarWalkStrategy(seed=42)
    original = "###Pure\nClass test::Person { name: String[1]; }"
    mutated = strategy.mutate(original, mutation_type="delete_token")
    assert mutated != original
    assert len(mutated) < len(original)


def test_mutate_swap_tokens():
    strategy = GrammarWalkStrategy(seed=42)
    original = "###Pure\nClass test::Person { name: String[1]; }"
    mutated = strategy.mutate(original, mutation_type="swap_tokens")
    assert mutated != original


def test_generate_batch():
    strategy = GrammarWalkStrategy(seed=42)
    batch = strategy.generate_batch(count=5, section_type="pure")
    assert len(batch) == 5
    for code in batch:
        assert "###Pure" in code
