# Legend Engine DSL Fuzzer — Design Spec

## Goals

1. **Parser robustness** — find crashes and unhandled exceptions in the ANTLR4 parsers for M2M mapping and relational DSLs
2. **Compiler bug finding** — generate syntactically valid code that exposes semantic bugs in the Pure compiler
3. **Test coverage expansion** — generate valid Pure programs that exercise untested code paths and can be converted into real unit tests

## Architecture

Hybrid Python + Java system:

```
┌─────────────────┐     ┌──────────────────────┐
│  Corpus Miner   │────>│  Probabilistic Model │
│  (Python)       │     │  (per-grammar rule    │
│                 │     │   frequency tables)   │
└─────────────────┘     └──────────┬───────────┘
                                   │
                                   v
┌─────────────────┐     ┌──────────────────────┐
│  Grammar Fuzz   │<--->│  Document Composer    │
│  (Grammarinator │     │  (Python)             │
│   generators)   │     │  - shared symbol table│
└─────────────────┘     │  - section orchestrator│
                        └──────────┬───────────┘
                                   │ generated .pure files
                                   v
                        ┌──────────────────────┐
                        │  Java Harness         │
                        │  - PureGrammarParser  │
                        │  - PureModel compiler │
                        │  - classification     │
                        │  - coverage tracking  │
                        └──────────┬───────────┘
                                   │ feedback
                                   v
                        ┌──────────────────────┐
                        │  Result Store         │
                        │  - crashes saved      │
                        │  - coverage inputs    │
                        │  - test candidates    │
                        └──────────────────────┘
```

## Component Details

### 1. Corpus Miner

Two extraction pipelines feed the probabilistic model.

**1a. `.pure` file extraction:**
Read all ~199 `.pure` files from the repo test directories. Split by `###` section headers. Tag each section with its type (Pure, Mapping, Relational, etc.).

**1b. Java string extraction:**
Java test classes embed Pure code as concatenated strings, e.g.:
```java
test("###Mapping\n" +
     "Mapping test::mapping\n" + ...
```
A Python extractor:
1. Finds Java test files matching patterns (`Test*Grammar*.java`, `Test*Roundtrip*.java`, `Test*Compilation*.java`)
2. Reconstructs concatenated strings into complete Pure code blocks via regex/heuristic parsing
3. Splits by section headers

**1c. Probabilistic model building:**
For each extracted section, parse with the corresponding Grammarinator parser to get a parse tree. Then:
- Count frequency of each alternative chosen at each production rule
- Build bigram tables: given parent rule + position, what's the distribution over child rule alternatives
- Record terminal token distributions (identifier name patterns, integer ranges, string patterns)
- Store as JSON files per grammar (`relational_model.json`, `mapping_model.json`, `domain_model.json`)

### 2. Document Composer & Symbol Table

Orchestrates generation of full multi-section Pure documents with consistent cross-references.

**Symbol Table** — shared mutable context tracking all names in a document:
- `packages`: list of package paths
- `classes`: name, package, properties (name + type + multiplicity)
- `enums`: name, package, values
- `associations`: name, connected classes
- `databases`: name, package, schemas with tables
- `tables`: name, columns (name + type)
- `mappings`: name, package

**Generation order** (dependency-driven):
1. `###Pure` — generate classes, enums, associations. Populate symbol table.
2. `###Relational` — generate Database definitions. Create columns corresponding to class properties using probabilistic model. Populate table/column names.
3. `###Mapping` — generate mappings wiring classes to tables (relational) or classes to classes (M2M). Use symbol table for valid references.

**Grammarinator custom actions:**
- On generating a class name identifier: register in symbol table
- On generating a `qualifiedName` in mapping source: pick from symbol table's known classes
- On generating a column reference: pick from symbol table's known columns
- Probabilistic model controls structure; symbol table controls names.

### 3. Java Harness

Minimal Maven module. Reads JSON lines from stdin, writes JSON lines to stdout.

**Input:**
```json
{"id": "fuzz-00042", "code": "###Pure\nClass test::Person { name: String[1]; }\n###Mapping\n..."}
```

**Output:**
```json
{"id": "fuzz-00042", "result": "COMPILE_OK", "time_ms": 145}
```

**Classification:**
- `PARSE_OK` — parsed successfully
- `PARSE_ERROR` — expected parse failure (invalid syntax)
- `PARSE_CRASH` — unexpected exception during parsing (BUG)
- `COMPILE_OK` — parsed and compiled successfully
- `COMPILE_ERROR` — expected compilation error
- `COMPILE_CRASH` — unexpected exception during compilation (BUG)
- `TIMEOUT` — exceeded deadline (default 10s)

**Implementation:**
- Reuse `PureGrammarParser.newInstance().parseModel(code)` for parsing
- Compile with `PureModel(modelContextData)` for semantic checking
- Timeout via `ExecutorService` with configurable deadline
- Optional JaCoCo agent attachment for coverage tracking

**Coverage tracking — two levels:**
- Lightweight (default): classify only, ~1000s inputs/sec
- Coverage mode: JaCoCo agent attached, dump periodically. Inputs increasing coverage saved to corpus for further mutation.

### 4. Fuzzing Strategies

**Strategy 1: Grammar-walk (parser robustness)**
- Random walks over grammar rules, weighted by probabilistic model
- Boundary pushing: max depth trees, empty alternatives, unusual token combinations
- Near-valid mutations: delete a token, swap two tokens, inject garbage, duplicate a section
- Target: `PARSE_CRASH`

**Strategy 2: Semantically-guided (compiler bugs)**
- Document Composer with full symbol table coordination
- Semantic mutations applied to valid documents:
  - Change property type in class but not in mapping
  - Reference non-existent column
  - Circular inheritance
  - Wrong multiplicity in mapping
  - Duplicate property mappings
  - Conflicting enumeration mappings
- Target: `COMPILE_CRASH` (expected `COMPILE_ERROR` but got exception)

**Strategy 3: Coverage-guided (test expansion)**
- Seed with mined corpus
- Population-based: mutate/recombine existing inputs via Grammarinator
- JaCoCo coverage feedback to keep inputs hitting new code paths
- `COMPILE_OK` inputs with new coverage saved as test candidates
- Periodic reporting on grammar rules and compiler paths with no existing test coverage
- Target: valid Pure programs that become real unit tests

**Campaign CLI:**
```
./fuzz.py --strategy grammar-walk --duration 1h --harness java
./fuzz.py --strategy semantic --duration 2h --harness java
./fuzz.py --strategy coverage --duration 4h --harness java --jacoco
./fuzz.py --report
```

### 5. Target Grammars

**Core (required by all sections):**
- `M3ParserGrammar.g4` / `M3LexerGrammar.g4` — expressions, types, multiplicity
- `CoreParserGrammar.g4` / `CoreLexerGrammar.g4` — qualified names, packages
- `DomainParserGrammar.g4` / `DomainLexerGrammar.g4` — classes, enums, associations

**Mapping DSL:**
- `MappingParserGrammar.g4` / `MappingLexerGrammar.g4` — mapping structure, includes, test suites
- `PureInstanceClassMappingParserGrammar.g4` / `PureInstanceClassMappingLexerGrammar.g4` — M2M property mappings
- `EnumerationMappingParserGrammar.g4` / `EnumerationMappingLexerGrammar.g4`
- `OperationClassMappingParserGrammar.g4` / `OperationClassMappingLexerGrammar.g4`
- `XStoreAssociationMappingParserGrammar.g4` / `XStoreAssociationMappingLexerGrammar.g4`

**Relational DSL:**
- `RelationalParserGrammar.g4` / `RelationalLexerGrammar.g4` — databases, schemas, tables, views, joins, filters
- `RelationalDatabaseConnectionParserGrammar.g4` / `RelationalDatabaseConnectionLexerGrammar.g4`
- `DataSourceSpecificationParserGrammar.g4` / `DataSourceSpecificationLexerGrammar.g4`
- `AuthenticationStrategyParserGrammar.g4` / `AuthenticationStrategyLexerGrammar.g4`

## Project Structure

```
legend-engine-fuzz/
├── harness/                          # Java Maven module
│   ├── pom.xml
│   └── src/main/java/.../
│       └── FuzzHarness.java
│
├── fuzzer/                           # Python package
│   ├── pyproject.toml
│   ├── fuzzer/
│   │   ├── miner/
│   │   │   ├── pure_extractor.py
│   │   │   ├── java_extractor.py
│   │   │   └── model_builder.py
│   │   ├── composer/
│   │   │   ├── symbol_table.py
│   │   │   ├── document_composer.py
│   │   │   └── actions/
│   │   │       ├── pure_actions.py
│   │   │       ├── relational_actions.py
│   │   │       └── mapping_actions.py
│   │   ├── strategies/
│   │   │   ├── grammar_walk.py
│   │   │   ├── semantic.py
│   │   │   └── coverage.py
│   │   ├── harness_client.py
│   │   └── reporter.py
│   └── tests/
│
├── grammars/
│   ├── core/
│   ├── mapping/
│   └── relational/
│
├── corpus/
│   ├── raw/
│   └── processed/
│
├── models/
│
├── results/
│   ├── crashes/
│   ├── test_candidates/
│   └── reports/
│
└── fuzz.py
```

### Dependencies

**Java harness:**
- `legend-engine-language-pure-grammar`
- `legend-engine-language-pure-compiler`
- `legend-engine-protocol-pure`

**Python fuzzer:**
- `grammarinator >= 23.7`
- `antlerinator`
- `antlr4-tools`
