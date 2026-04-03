# Legend Engine DSL Fuzzer

A hybrid Python/Java fuzzing tool for Legend Engine's Pure DSL grammars. It
generates syntactically varied Pure code (classes, enums, M2M mappings,
relational mappings, database definitions) and feeds each input through the
real Legend Engine parser and compiler to surface bugs.

## Goals

1. **Parser robustness** -- find inputs that cause unexpected exceptions
   (crashes) rather than clean parse errors in `PureGrammarParser`.
2. **Compiler bugs** -- find inputs that parse successfully but trigger
   unexpected failures in `Compiler.compile`.
3. **Test coverage expansion** -- harvest inputs that both parse and compile
   (`COMPILE_OK`) as candidates for new test cases.


## Architecture

```
                        fuzz.py  (CLI orchestrator)
                           |
            +--------------+--------------+
            |                             |
   GrammarWalkStrategy            HarnessClient
     (Python generation            (subprocess,
      + mutation)                   JSON-lines)
            |                             |
   DocumentComposer               FuzzHarness.jar
   SymbolTable                    (Java, shaded uber-jar)
            |                             |
   Grammarinator generators       PureGrammarParser
   (from .g4 grammars)            Compiler.compile
            |                             |
   TokenModel                     result JSON
   (mined from corpus)                   |
                                  FuzzReporter
                                  (crashes, test
                                   candidates, reports)
```

The Python side generates Pure DSL source text and applies mutations. The Java
side parses and compiles each input using the real Legend Engine stack, then
returns a classification. Communication happens over stdin/stdout using one
JSON object per line.


## Prerequisites

| Requirement | Version |
|---|---|
| Java (JDK) | 11+ |
| Maven | 3.8+ |
| Python | 3.10+ |
| legend-engine | Built locally on the same branch/version |

The Java harness depends on Legend Engine artifacts installed in your local
Maven repository. Build legend-engine (at least `mvn install -DskipTests` on
the modules listed in `harness/pom.xml`) before building the harness.


## Quick Start

All paths below are relative to the repository root.

### 1. Build the Java harness

```bash
cd harness
mvn package -DskipTests
```

This produces the shaded JAR at:

```
harness/target/legend-engine-fuzz-harness-fuzz-SNAPSHOT.jar
```

### 2. Set up the Python environment

```bash
cd fuzzer
python3 -m venv .venv
source .venv/bin/activate
pip install -e .
pip install -e ".[dev]"   # includes pytest
```

### 3. Collect and process grammars (optional)

If you want to regenerate the Grammarinator generators from the ANTLR `.g4`
files:

```bash
# Collect .g4 files from the engine source tree
./scripts/collect_grammars.sh

# Generate Grammarinator Python generators
./scripts/process_grammars.sh
```

Pre-generated generators are already committed under
`fuzzer/fuzzer/generated/`.

### 4. Mine the corpus (optional)

Extract Pure DSL snippets from the legend-engine source tree to build
probabilistic token models:

```bash
cd fuzzer
source .venv/bin/activate
python -m fuzzer.miner /path/to/legend-engine --output corpus/raw
```

This scans `.pure` files and Java test files for inline Pure grammar strings,
then writes JSONL files grouped by section type (e.g.,
`pure_sections.jsonl`, `mapping_sections.jsonl`).

### 5. Run a fuzz campaign

```bash
python fuzz.py grammar-walk --count 1000
```

Results are written to `results/` by default. Crashes appear immediately on
stdout.


## Components

### Java Harness (`harness/`)

`FuzzHarness.java` is a long-running Java process that reads JSON requests
from stdin, parses each `code` field with `PureGrammarParser`, then attempts
`Compiler.compile`. Each input is run inside a `Future` with a configurable
timeout. Results are classified into one of the result types described below
and written as JSON to stdout.

The harness is packaged as a shaded uber-jar via `maven-shade-plugin` so it
can be launched with a simple `java -jar` command.

Key dependencies:

- `legend-engine-language-pure-grammar` -- Pure DSL parser
- `legend-engine-language-pure-compiler` -- Pure compiler
- `legend-engine-extensions-collection-generation` -- extension loading via
  `ServiceLoader`

### Corpus Miner (`fuzzer/fuzzer/miner/`)

- **`pure_extractor.py`** -- walks a directory tree for `.pure` files and
  extracts section bodies (splitting on `###` headers).
- **`java_extractor.py`** -- scans Java test files for inline Pure grammar
  strings (string literals containing `###`).
- **`model_builder.py`** -- builds a `TokenModel` from extracted sections:
  frequency distributions over identifiers, types, multiplicities, class
  names, and property names. The model is used to sample realistic tokens
  during generation.

### Document Composer (`fuzzer/fuzzer/composer/`)

- **`symbol_table.py`** -- tracks classes, enums, databases, tables, and
  mappings that have been emitted so cross-references stay consistent.
- **`document_composer.py`** -- orchestrates generation of multi-section Pure
  documents. It can produce:
  - `###Pure` sections (classes and enums)
  - `###Relational` sections (databases with tables derived from class
    properties)
  - `###Mapping` sections (M2M pure-instance mappings or relational mappings)
  - Full documents combining all of the above

The composer maps Pure types to SQL column types (`String` to `VARCHAR(200)`,
`Integer` to `INTEGER`, etc.), generates random but internally consistent
packages, and wires up mapping property assignments to match source/target
class shapes.

### Fuzzing Strategies (`fuzzer/fuzzer/strategies/`)

- **`grammar_walk.py`** -- the primary strategy. It generates a document via
  `DocumentComposer`, then optionally applies one of four mutations:
  - `delete_token` -- remove a random non-whitespace token
  - `swap_tokens` -- swap two random non-whitespace tokens
  - `duplicate_token` -- duplicate a random token in place
  - `inject_garbage` -- insert random punctuation characters

  The CLI alternates between clean generation (every 3rd input) and mutated
  generation.

  Section types cycled through: `pure`, `relational`, `full_m2m`,
  `full_relational`.

### Grammarinator Generators (`fuzzer/fuzzer/generated/`)

Python modules generated from the Legend Engine ANTLR `.g4` grammars by
Grammarinator. Currently generated for:

- `DomainLexerGrammarGenerator.py` -- Pure domain (classes, enums)
- `MappingLexerGrammarGenerator.py` -- mapping definitions
- `PureInstanceClassMappingLexerGrammarGenerator.py` -- M2M class mappings
- `RelationalLexerGrammarGenerator.py` -- relational store definitions

These can be used as an alternative generation backend to the hand-written
`DocumentComposer`.

### Harness Client (`fuzzer/fuzzer/harness_client.py`)

Python wrapper around the Java subprocess. Manages process lifecycle
(`start`/`stop`, context manager), serialises requests as JSON, and
deserialises result lines. Used by all fuzzing strategies.

### Reporter (`fuzzer/fuzzer/reporter.py`)

Collects results during a campaign and writes:

- Crash files (JSON with code + stack trace) to `results/crashes/`
- Test candidate files (`.pure` source) to `results/test_candidates/`
- A summary report (JSON) to `results/reports/summary.json`
- A console summary at the end of each run


## CLI Reference

### `fuzz.py` -- main entry point

```
usage: fuzz.py [-h] [--results-dir DIR] [--jar JAR] [--timeout MS]
               [--seed SEED] {grammar-walk,report} ...
```

**Global options:**

| Flag | Default | Description |
|---|---|---|
| `--results-dir` | `results` | Base directory for output |
| `--jar` | auto-detected | Path to `legend-engine-fuzz-harness-*.jar` |
| `--timeout` | `10000` | Timeout per input in milliseconds |
| `--seed` | none | Random seed for reproducibility |

**Subcommands:**

#### `grammar-walk`

Generate and mutate Pure DSL documents, classify each through the harness.

```bash
python fuzz.py grammar-walk --count 5000
python fuzz.py --seed 42 --timeout 30000 grammar-walk --count 500
```

| Flag | Default | Description |
|---|---|---|
| `--count` | `1000` | Number of inputs to generate |

#### `report`

Print a summary of existing results (crash count and test candidate count).

```bash
python fuzz.py report
python fuzz.py --results-dir my_run report
```

### Corpus Miner

```
usage: python -m fuzzer.miner ENGINE_ROOT [-o OUTPUT_DIR]
```

| Argument | Default | Description |
|---|---|---|
| `ENGINE_ROOT` | (required) | Path to legend-engine root |
| `--output`, `-o` | `corpus/raw` | Directory for extracted section files |

Output is written as JSONL files, one per section type.


## Result Classification

Each input is classified into exactly one of these categories:

| Result | Meaning |
|---|---|
| `PARSE_ERROR` | Parser threw an expected exception (`EngineException` or `ParseCancellationException`). Normal for malformed input. |
| `PARSE_CRASH` | Parser threw an unexpected exception. **This is a bug.** Saved to `results/crashes/`. |
| `COMPILE_OK` | Parsed and compiled successfully. Saved to `results/test_candidates/` as a `.pure` file. |
| `COMPILE_ERROR` | Parsed OK but compiler threw an expected `EngineException`. Normal for semantically invalid input. |
| `COMPILE_CRASH` | Parsed OK but compiler threw an unexpected exception. **This is a bug.** Saved to `results/crashes/`. |
| `TIMEOUT` | Processing exceeded the configured timeout. May indicate an infinite loop or pathological input. |
| `HARNESS_ERROR` | The harness itself failed to process the request (e.g., malformed JSON). |

The distinction between "error" and "crash" is based on exception type.
Expected exceptions (those whose class name contains `EngineException` or
`ParseCancellationException`) are classified as errors. Everything else --
`NullPointerException`, `StackOverflowError`, `ClassCastException`, etc. --
is classified as a crash and is likely a real bug.


## Output Structure

```
results/
  crashes/              Crash-inducing inputs (JSON with code + stack trace)
    gw-a1b2c3d4.json
  test_candidates/      Inputs that parsed and compiled successfully (.pure)
    gw-e5f6a7b8.pure
  reports/
    summary.json        Aggregate statistics for the campaign
```

Each crash file contains:

```json
{
  "id": "gw-a1b2c3d4",
  "code": "###Pure\nClass pkg::Foo\n{\n  name: String[1];\n}\n...",
  "result": "PARSE_CRASH",
  "exception": "java.lang.NullPointerException",
  "message": "...",
  "stacktrace": "...",
  "time_ms": 42
}
```

The summary report aggregates counts:

```json
{
  "total": 1000,
  "breakdown": {
    "PARSE_ERROR": 312,
    "COMPILE_OK": 45,
    "COMPILE_ERROR": 640,
    "PARSE_CRASH": 2,
    "COMPILE_CRASH": 1
  },
  "crashes": 3,
  "test_candidates": 45
}
```


## Grammars

The fuzzer operates on ANTLR `.g4` grammars collected from the engine source
tree. The `scripts/collect_grammars.sh` script copies them into three groups:

| Directory | Count | Contents |
|---|---|---|
| `grammars/core/` | 13 | Core, Domain, M3, Code, Connection, ModelConnection |
| `grammars/mapping/` | 14 | Mapping, PureInstanceClassMapping, EnumerationMapping, OperationClassMapping, XStoreAssociationMapping, AggregationAware, RelationFunctionMapping |
| `grammars/relational/` | 8 | Relational, RelationalDatabaseConnection, DataSourceSpecification, AuthenticationStrategy |


## Extending

### Adding a new DSL grammar

1. Add the `.g4` file copy commands to `scripts/collect_grammars.sh`.
2. Add a `grammarinator-process` invocation to `scripts/process_grammars.sh`.
3. Run both scripts to regenerate.
4. Add a new section generator method to `DocumentComposer` (e.g.,
   `generate_service_section`) and update `SymbolTable` if the new grammar
   introduces symbols that other sections may reference.
5. Add the new section type to `GrammarWalkStrategy.generate`.
6. Add the section type string to the `section_types` list in `fuzz.py`.

### Adding a new fuzzing strategy

1. Create a new module under `fuzzer/fuzzer/strategies/` implementing at
   minimum a `generate(section_type) -> str` method.
2. Add a new subcommand to `fuzz.py` that instantiates your strategy and
   feeds results through `HarnessClient` and `FuzzReporter`.

### Adding a new mutation type

1. Add a new branch to `GrammarWalkStrategy.mutate` keyed by a new
   `mutation_type` string.
2. Add the string to the `mutation_types` list in `fuzz.py`.


## Project Structure

```
legend-engine-fuzz/
  fuzz.py                          CLI entry point
  README.md                        This file
  harness/
    pom.xml                        Maven build for the Java harness
    src/main/java/.../
      FuzzHarness.java             Parse + compile harness (stdin/stdout JSON)
  fuzzer/
    pyproject.toml                 Python package definition (grammarinator, antlerinator)
    .venv/                         Python virtual environment (gitignored)
    fuzzer/
      __init__.py
      harness_client.py            Subprocess wrapper for FuzzHarness.jar
      reporter.py                  Result collection and reporting
      composer/
        __init__.py
        symbol_table.py            Tracks emitted symbols for cross-references
        document_composer.py       Multi-section Pure document generator
      strategies/
        __init__.py
        grammar_walk.py            Generation + mutation strategy
      miner/
        __init__.py
        __main__.py                Corpus mining CLI
        pure_extractor.py          Extract sections from .pure files
        java_extractor.py          Extract Pure strings from Java tests
        model_builder.py           Build probabilistic token models
      generated/
        DomainLexerGrammarGenerator.py
        MappingLexerGrammarGenerator.py
        PureInstanceClassMappingLexerGrammarGenerator.py
        RelationalLexerGrammarGenerator.py
    tests/
      test_document_composer.py
      test_grammar_walk.py
      test_harness_client.py
      test_java_extractor.py
      test_model_builder.py
      test_pure_extractor.py
      test_symbol_table.py
  grammars/
    core/                          13 .g4 files (Domain, M3, Core, etc.)
    mapping/                       14 .g4 files (Mapping, M2M, etc.)
    relational/                     8 .g4 files (Relational, connections)
  scripts/
    collect_grammars.sh            Copy .g4 files from engine source tree
    process_grammars.sh            Run grammarinator-process on collected grammars
  results/                         (gitignored) output from fuzz campaigns
```


## Running Tests

```bash
cd fuzzer
source .venv/bin/activate
pytest
```

To run with coverage:

```bash
pytest --cov=fuzzer --cov-report=term-missing
```
