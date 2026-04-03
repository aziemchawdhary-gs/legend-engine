from fuzzer.composer.document_composer import DocumentComposer


def test_compose_pure_section():
    composer = DocumentComposer(seed=42)
    doc = composer.generate_pure_section(num_classes=2, num_enums=1)
    assert "###Pure" in doc
    assert "Class" in doc
    assert "Enum" in doc
    assert len(composer.symbol_table.get_class_names()) == 2
    assert len(composer.symbol_table.get_enum_names()) == 1


def test_compose_relational_section():
    composer = DocumentComposer(seed=42)
    composer.generate_pure_section(num_classes=1)
    doc = composer.generate_relational_section(num_databases=1)
    assert "###Relational" in doc
    assert "Database" in doc
    assert "Table" in doc
    assert len(composer.symbol_table.get_database_names()) == 1


def test_compose_m2m_mapping_section():
    composer = DocumentComposer(seed=42)
    composer.generate_pure_section(num_classes=2)
    doc = composer.generate_m2m_mapping_section()
    assert "###Mapping" in doc
    assert "Mapping" in doc
    assert "Pure" in doc


def test_compose_full_document():
    composer = DocumentComposer(seed=42)
    doc = composer.generate_full_document(
        num_classes=2,
        num_enums=1,
        num_databases=0,
        mapping_type="m2m",
    )
    assert "###Pure" in doc
    assert "###Mapping" in doc
    assert "Class" in doc


def test_compose_relational_mapping_document():
    composer = DocumentComposer(seed=42)
    doc = composer.generate_full_document(
        num_classes=1,
        num_enums=0,
        num_databases=1,
        mapping_type="relational",
    )
    assert "###Pure" in doc
    assert "###Relational" in doc
    assert "###Mapping" in doc
