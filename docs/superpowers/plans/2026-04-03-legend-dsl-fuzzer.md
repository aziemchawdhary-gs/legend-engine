# Legend DSL Fuzzer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a hybrid Python/Java fuzzer that generates test cases for legend-engine M2M and relational DSLs using ANTLR grammar fuzzing and corpus-mined probabilistic models.

**Architecture:** Python (Grammarinator) generates Pure DSL code from grammar definitions, guided by frequency tables mined from existing tests. A Java harness classifies each input as parse/compile success/error/crash. A document composer coordinates multi-section generation with a shared symbol table.

**Tech Stack:** Python 3.10+, Grammarinator >= 23.7, ANTLR4, Java 8 (legend-engine), Jackson for JSON-lines IPC, JaCoCo for coverage.

---

## File Structure

```
legend-engine-fuzz/          # NEW top-level directory (not a Maven submodule of root pom)
├── harness/
│   ├── pom.xml
│   └── src/main/java/org/finos/legend/engine/fuzz/
│       └── FuzzHarness.java
│
├── fuzzer/
│   ├── pyproject.toml
│   ├── fuzzer/
│   │   ├── __init__.py
│   │   ├── miner/
│   │   │   ├── __init__.py
│   │   │   ├── pure_extractor.py
│   │   │   ├── java_extractor.py
│   │   │   └── model_builder.py
│   │   ├── composer/
│   │   │   ├── __init__.py
│   │   │   ├── symbol_table.py
│   │   │   └── document_composer.py
│   │   ├── strategies/
│   │   │   ├── __init__.py
│   │   │   ├── grammar_walk.py
│   │   │   ├── semantic.py
│   │   │   └── coverage.py
│   │   ├── harness_client.py
│   │   └── reporter.py
│   └── tests/
│       ├── __init__.py
│       ├── test_pure_extractor.py
│       ├── test_java_extractor.py
│       ├── test_model_builder.py
│       ├── test_symbol_table.py
│       ├── test_document_composer.py
│       ├── test_harness_client.py
│       └── test_grammar_walk.py
│
├── grammars/                # copies of .g4 files grouped for Grammarinator
│   ├── core/
│   ├── mapping/
│   └── relational/
│
├── corpus/
│   ├── raw/
│   └── processed/
│
├── models/                  # probabilistic model JSON files
│
├── results/
│   ├── crashes/
│   ├── test_candidates/
│   └── reports/
│
├── scripts/
│   ├── collect_grammars.sh
│   └── process_grammars.sh
│
└── fuzz.py                  # main CLI entrypoint
```

---

### Task 1: Java Harness — Maven Project Setup

**Files:**
- Create: `legend-engine-fuzz/harness/pom.xml`

- [ ] **Step 1: Create the harness pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.finos.legend.engine</groupId>
        <artifactId>legend-engine</artifactId>
        <version>fuzz-SNAPSHOT</version>
        <relativePath>../../pom.xml</relativePath>
    </parent>

    <artifactId>legend-engine-fuzz-harness</artifactId>
    <name>Legend Engine - Fuzz Harness</name>

    <dependencies>
        <!-- Grammar parser -->
        <dependency>
            <groupId>org.finos.legend.engine</groupId>
            <artifactId>legend-engine-language-pure-grammar</artifactId>
        </dependency>
        <!-- Compiler -->
        <dependency>
            <groupId>org.finos.legend.engine</groupId>
            <artifactId>legend-engine-language-pure-compiler</artifactId>
        </dependency>
        <!-- All extensions (grammar + compiler) so relational, M2M, etc. are on classpath -->
        <dependency>
            <groupId>org.finos.legend.engine</groupId>
            <artifactId>legend-engine-extensions-collection-generation</artifactId>
        </dependency>
        <!-- Jackson for JSON-lines I/O -->
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-shade-plugin</artifactId>
                <version>3.5.1</version>
                <executions>
                    <execution>
                        <phase>package</phase>
                        <goals><goal>shade</goal></goals>
                        <configuration>
                            <transformers>
                                <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                                    <mainClass>org.finos.legend.engine.fuzz.FuzzHarness</mainClass>
                                </transformer>
                                <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
                            </transformers>
                            <filters>
                                <filter>
                                    <artifact>*:*</artifact>
                                    <excludes>
                                        <exclude>META-INF/*.SF</exclude>
                                        <exclude>META-INF/*.DSA</exclude>
                                        <exclude>META-INF/*.RSA</exclude>
                                    </excludes>
                                </filter>
                            </filters>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Create directory structure**

Run:
```bash
mkdir -p legend-engine-fuzz/harness/src/main/java/org/finos/legend/engine/fuzz
```

- [ ] **Step 3: Verify Maven resolves dependencies**

Run:
```bash
cd legend-engine-fuzz/harness && mvn dependency:resolve -q
```
Expected: BUILD SUCCESS (dependencies resolve from local .m2 since legend-engine is already built)

- [ ] **Step 4: Commit**

```bash
git add legend-engine-fuzz/harness/pom.xml
git commit -m "feat(fuzz): add Java harness Maven project skeleton"
```

---

### Task 2: Java Harness — FuzzHarness Implementation

**Files:**
- Create: `legend-engine-fuzz/harness/src/main/java/org/finos/legend/engine/fuzz/FuzzHarness.java`

- [ ] **Step 1: Write FuzzHarness.java**

```java
package org.finos.legend.engine.fuzz;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.finos.legend.engine.language.pure.compiler.Compiler;
import org.finos.legend.engine.language.pure.compiler.toPureGraph.PureModel;
import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.finos.legend.engine.protocol.pure.v1.model.context.PureModelContextData;
import org.finos.legend.engine.shared.core.deployment.DeploymentMode;
import org.finos.legend.engine.shared.core.identity.Identity;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.concurrent.*;

public class FuzzHarness
{
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long DEFAULT_TIMEOUT_MS = 10000;
    private static final PureGrammarParser PARSER = PureGrammarParser.newInstance();

    public static void main(String[] args) throws Exception
    {
        long timeoutMs = DEFAULT_TIMEOUT_MS;
        for (int i = 0; i < args.length; i++)
        {
            if ("--timeout".equals(args[i]) && i + 1 < args.length)
            {
                timeoutMs = Long.parseLong(args[i + 1]);
            }
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        PrintWriter writer = new PrintWriter(System.out, true);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        String line;
        while ((line = reader.readLine()) != null)
        {
            ObjectNode result = processInput(line, executor, timeoutMs);
            writer.println(MAPPER.writeValueAsString(result));
        }

        executor.shutdownNow();
    }

    private static ObjectNode processInput(String jsonLine, ExecutorService executor, long timeoutMs)
    {
        ObjectNode result = MAPPER.createObjectNode();
        try
        {
            ObjectNode input = (ObjectNode) MAPPER.readTree(jsonLine);
            String id = input.get("id").asText();
            String code = input.get("code").asText();
            result.put("id", id);

            long start = System.currentTimeMillis();
            Future<ObjectNode> future = executor.submit(() -> classify(code));

            try
            {
                ObjectNode classification = future.get(timeoutMs, TimeUnit.MILLISECONDS);
                long elapsed = System.currentTimeMillis() - start;
                result.setAll(classification);
                result.put("time_ms", elapsed);
            }
            catch (TimeoutException e)
            {
                future.cancel(true);
                result.put("result", "TIMEOUT");
                result.put("time_ms", timeoutMs);
            }
        }
        catch (Exception e)
        {
            result.put("result", "HARNESS_ERROR");
            result.put("exception", e.getClass().getName());
            result.put("message", e.getMessage());
        }
        return result;
    }

    private static ObjectNode classify(String code)
    {
        ObjectNode result = MAPPER.createObjectNode();

        // Phase 1: Parse
        PureModelContextData pmcd;
        try
        {
            pmcd = PARSER.parseModel(code);
        }
        catch (Exception e)
        {
            if (isExpectedParseError(e))
            {
                result.put("result", "PARSE_ERROR");
                result.put("message", e.getMessage());
            }
            else
            {
                result.put("result", "PARSE_CRASH");
                result.put("exception", e.getClass().getName());
                result.put("message", e.getMessage());
                result.put("stacktrace", getStackTrace(e));
            }
            return result;
        }
        result.put("parse", "OK");

        // Phase 2: Compile
        try
        {
            PureModel pureModel = Compiler.compile(pmcd, DeploymentMode.TEST, Identity.getAnonymousIdentity().getName());
            result.put("result", "COMPILE_OK");
        }
        catch (Exception e)
        {
            if (isExpectedCompileError(e))
            {
                result.put("result", "COMPILE_ERROR");
                result.put("message", e.getMessage());
            }
            else
            {
                result.put("result", "COMPILE_CRASH");
                result.put("exception", e.getClass().getName());
                result.put("message", e.getMessage());
                result.put("stacktrace", getStackTrace(e));
            }
        }
        return result;
    }

    private static boolean isExpectedParseError(Exception e)
    {
        // EngineException with PARSER error type is an expected parse failure
        String name = e.getClass().getName();
        return name.contains("EngineException") || name.contains("ParseCancellationException");
    }

    private static boolean isExpectedCompileError(Exception e)
    {
        String name = e.getClass().getName();
        return name.contains("EngineException");
    }

    private static String getStackTrace(Exception e)
    {
        java.io.StringWriter sw = new java.io.StringWriter();
        e.printStackTrace(new java.io.PrintWriter(sw));
        String trace = sw.toString();
        // Truncate to first 2000 chars to avoid massive JSON lines
        return trace.length() > 2000 ? trace.substring(0, 2000) : trace;
    }
}
```

- [ ] **Step 2: Compile**

Run:
```bash
cd legend-engine-fuzz/harness && mvn compile -q
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Test manually with a valid input**

Run:
```bash
cd legend-engine-fuzz/harness && mvn package -q -DskipTests
echo '{"id":"test-1","code":"###Pure\nClass test::Person { name: String[1]; }"}' | java -jar target/legend-engine-fuzz-harness-fuzz-SNAPSHOT.jar
```
Expected: JSON output with `"result": "COMPILE_OK"`

- [ ] **Step 4: Test with invalid input**

Run:
```bash
echo '{"id":"test-2","code":"###Pure\nClass {{{ invalid"}' | java -jar legend-engine-fuzz/harness/target/legend-engine-fuzz-harness-fuzz-SNAPSHOT.jar
```
Expected: JSON output with `"result": "PARSE_ERROR"`

- [ ] **Step 5: Commit**

```bash
git add legend-engine-fuzz/harness/src/
git commit -m "feat(fuzz): implement Java fuzz harness with parse+compile classification"
```

---

### Task 3: Python Project Setup

**Files:**
- Create: `legend-engine-fuzz/fuzzer/pyproject.toml`
- Create: `legend-engine-fuzz/fuzzer/fuzzer/__init__.py`
- Create: all `__init__.py` files for subpackages

- [ ] **Step 1: Create pyproject.toml**

```toml
[build-system]
requires = ["setuptools>=68.0"]
build-backend = "setuptools.build_meta"

[project]
name = "legend-engine-fuzzer"
version = "0.1.0"
description = "Grammar-based fuzzer for Legend Engine DSLs"
requires-python = ">=3.10"
dependencies = [
    "grammarinator>=23.7",
    "antlerinator>=1.2.0",
]

[project.optional-dependencies]
dev = [
    "pytest>=7.0",
    "pytest-cov>=4.0",
]

[tool.pytest.ini_options]
testpaths = ["tests"]
```

- [ ] **Step 2: Create package structure**

Run:
```bash
mkdir -p legend-engine-fuzz/fuzzer/fuzzer/{miner,composer,strategies}
mkdir -p legend-engine-fuzz/fuzzer/tests
touch legend-engine-fuzz/fuzzer/fuzzer/__init__.py
touch legend-engine-fuzz/fuzzer/fuzzer/miner/__init__.py
touch legend-engine-fuzz/fuzzer/fuzzer/composer/__init__.py
touch legend-engine-fuzz/fuzzer/fuzzer/strategies/__init__.py
touch legend-engine-fuzz/fuzzer/tests/__init__.py
```

- [ ] **Step 3: Create venv and install**

Run:
```bash
cd legend-engine-fuzz/fuzzer && python3 -m venv .venv && source .venv/bin/activate && pip install -e ".[dev]"
```
Expected: Successfully installed grammarinator, antlerinator, pytest

- [ ] **Step 4: Verify imports**

Run:
```bash
cd legend-engine-fuzz/fuzzer && .venv/bin/python -c "import grammarinator; print(grammarinator.__version__)"
```
Expected: version number >= 23.7

- [ ] **Step 5: Commit**

```bash
git add legend-engine-fuzz/fuzzer/
git commit -m "feat(fuzz): add Python fuzzer project skeleton with grammarinator dependency"
```

---

### Task 4: Grammar Collection

**Files:**
- Create: `legend-engine-fuzz/scripts/collect_grammars.sh`
- Create: `legend-engine-fuzz/grammars/core/` (populated by script)
- Create: `legend-engine-fuzz/grammars/mapping/` (populated by script)
- Create: `legend-engine-fuzz/grammars/relational/` (populated by script)

- [ ] **Step 1: Write the grammar collection script**

```bash
#!/bin/bash
# Collects .g4 grammar files from legend-engine into grouped directories for Grammarinator
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
FUZZ_DIR="$(dirname "$SCRIPT_DIR")"
ENGINE_DIR="$(dirname "$FUZZ_DIR")"

GRAMMAR_DIR="$FUZZ_DIR/grammars"
rm -rf "$GRAMMAR_DIR"
mkdir -p "$GRAMMAR_DIR"/{core,mapping,relational}

CORE_SRC="$ENGINE_DIR/legend-engine-core/legend-engine-core-base/legend-engine-core-language-pure/legend-engine-language-pure-grammar/src/main/antlr4/org/finos/legend/engine/language/pure/grammar/from/antlr4"

# Core grammars
cp "$CORE_SRC/core/CoreParserGrammar.g4" "$GRAMMAR_DIR/core/"
cp "$CORE_SRC/core/CoreLexerGrammar.g4" "$GRAMMAR_DIR/core/"
cp "$CORE_SRC/core/CoreFragmentGrammar.g4" "$GRAMMAR_DIR/core/"
cp "$CORE_SRC/core/M3ParserGrammar.g4" "$GRAMMAR_DIR/core/"
cp "$CORE_SRC/core/M3LexerGrammar.g4" "$GRAMMAR_DIR/core/"
cp "$CORE_SRC/domain/DomainParserGrammar.g4" "$GRAMMAR_DIR/core/"
cp "$CORE_SRC/domain/DomainLexerGrammar.g4" "$GRAMMAR_DIR/core/"
cp "$CORE_SRC/CodeParserGrammar.g4" "$GRAMMAR_DIR/core/"
cp "$CORE_SRC/CodeLexerGrammar.g4" "$GRAMMAR_DIR/core/"
cp "$CORE_SRC/connection/ConnectionParserGrammar.g4" "$GRAMMAR_DIR/core/"
cp "$CORE_SRC/connection/ConnectionLexerGrammar.g4" "$GRAMMAR_DIR/core/"
cp "$CORE_SRC/connection/modelConnection/ModelConnectionParserGrammar.g4" "$GRAMMAR_DIR/core/"
cp "$CORE_SRC/connection/modelConnection/ModelConnectionLexerGrammar.g4" "$GRAMMAR_DIR/core/"

# Mapping grammars
cp "$CORE_SRC/mapping/MappingParserGrammar.g4" "$GRAMMAR_DIR/mapping/"
cp "$CORE_SRC/mapping/MappingLexerGrammar.g4" "$GRAMMAR_DIR/mapping/"
cp "$CORE_SRC/mapping/pureInstanceClassMapping/PureInstanceClassMappingParserGrammar.g4" "$GRAMMAR_DIR/mapping/"
cp "$CORE_SRC/mapping/pureInstanceClassMapping/PureInstanceClassMappingLexerGrammar.g4" "$GRAMMAR_DIR/mapping/"
cp "$CORE_SRC/mapping/enumerationMapping/EnumerationMappingParserGrammar.g4" "$GRAMMAR_DIR/mapping/"
cp "$CORE_SRC/mapping/enumerationMapping/EnumerationMappingLexerGrammar.g4" "$GRAMMAR_DIR/mapping/"
cp "$CORE_SRC/mapping/operationClassMapping/OperationClassMappingParserGrammar.g4" "$GRAMMAR_DIR/mapping/"
cp "$CORE_SRC/mapping/operationClassMapping/OperationClassMappingLexerGrammar.g4" "$GRAMMAR_DIR/mapping/"
cp "$CORE_SRC/mapping/xStoreAssociationMapping/XStoreAssociationMappingParserGrammar.g4" "$GRAMMAR_DIR/mapping/"
cp "$CORE_SRC/mapping/xStoreAssociationMapping/XStoreAssociationMappingLexerGrammar.g4" "$GRAMMAR_DIR/mapping/"
cp "$CORE_SRC/mapping/aggregationAware/AggregationAwareParserGrammar.g4" "$GRAMMAR_DIR/mapping/"
cp "$CORE_SRC/mapping/aggregationAware/AggregationAwareLexerGrammar.g4" "$GRAMMAR_DIR/mapping/"
cp "$CORE_SRC/mapping/relationFunctionMapping/RelationFunctionMappingParserGrammar.g4" "$GRAMMAR_DIR/mapping/"
cp "$CORE_SRC/mapping/relationFunctionMapping/RelationFunctionMappingLexerGrammar.g4" "$GRAMMAR_DIR/mapping/"

# Relational grammars
REL_SRC="$ENGINE_DIR/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-grammar/src/main/antlr4/org/finos/legend/engine/language/pure/grammar/from/antlr4"
cp "$REL_SRC/RelationalParserGrammar.g4" "$GRAMMAR_DIR/relational/"
cp "$REL_SRC/RelationalLexerGrammar.g4" "$GRAMMAR_DIR/relational/"
cp "$REL_SRC/connection/RelationalDatabaseConnectionParserGrammar.g4" "$GRAMMAR_DIR/relational/"
cp "$REL_SRC/connection/RelationalDatabaseConnectionLexerGrammar.g4" "$GRAMMAR_DIR/relational/"
cp "$REL_SRC/connection/datasource/DataSourceSpecificationParserGrammar.g4" "$GRAMMAR_DIR/relational/"
cp "$REL_SRC/connection/datasource/DataSourceSpecificationLexerGrammar.g4" "$GRAMMAR_DIR/relational/"
cp "$REL_SRC/connection/authentication/AuthenticationStrategyParserGrammar.g4" "$GRAMMAR_DIR/relational/"
cp "$REL_SRC/connection/authentication/AuthenticationStrategyLexerGrammar.g4" "$GRAMMAR_DIR/relational/"

echo "Collected grammars into $GRAMMAR_DIR"
find "$GRAMMAR_DIR" -name "*.g4" | wc -l
echo "grammar files collected"
```

- [ ] **Step 2: Run the script**

Run:
```bash
chmod +x legend-engine-fuzz/scripts/collect_grammars.sh
legend-engine-fuzz/scripts/collect_grammars.sh
```
Expected: ~35 grammar files collected

- [ ] **Step 3: Commit**

```bash
git add legend-engine-fuzz/scripts/ legend-engine-fuzz/grammars/
git commit -m "feat(fuzz): add grammar collection script and collected .g4 files"
```

---

### Task 5: Grammar Processing with Grammarinator

**Files:**
- Create: `legend-engine-fuzz/scripts/process_grammars.sh`

- [ ] **Step 1: Write the grammar processing script**

Grammarinator needs to process each grammar pair (lexer + parser) into Python generators. Because legend-engine grammars use `import` directives (e.g., `M3ParserGrammar` imports `CoreParserGrammar`), we need to place all grammars in a flat directory for ANTLR resolution, then process them.

```bash
#!/bin/bash
# Process .g4 grammars into Grammarinator generators
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
FUZZ_DIR="$(dirname "$SCRIPT_DIR")"
GRAMMAR_DIR="$FUZZ_DIR/grammars"
GEN_DIR="$FUZZ_DIR/fuzzer/fuzzer/generated"

# Create a flat working directory with all grammars (ANTLR import resolution)
WORK_DIR=$(mktemp -d)
find "$GRAMMAR_DIR" -name "*.g4" -exec cp {} "$WORK_DIR/" \;

mkdir -p "$GEN_DIR"

cd "$FUZZ_DIR/fuzzer"
source .venv/bin/activate

# Process Domain grammar (classes, enums — the ###Pure section)
grammarinator-process "$WORK_DIR/DomainLexerGrammar.g4" "$WORK_DIR/DomainParserGrammar.g4" \
    -o "$GEN_DIR" \
    --lib-dir "$WORK_DIR"

# Process Mapping grammar
grammarinator-process "$WORK_DIR/MappingLexerGrammar.g4" "$WORK_DIR/MappingParserGrammar.g4" \
    -o "$GEN_DIR" \
    --lib-dir "$WORK_DIR"

# Process PureInstanceClassMapping grammar (M2M)
grammarinator-process "$WORK_DIR/PureInstanceClassMappingLexerGrammar.g4" "$WORK_DIR/PureInstanceClassMappingParserGrammar.g4" \
    -o "$GEN_DIR" \
    --lib-dir "$WORK_DIR"

# Process Relational grammar
grammarinator-process "$WORK_DIR/RelationalLexerGrammar.g4" "$WORK_DIR/RelationalParserGrammar.g4" \
    -o "$GEN_DIR" \
    --lib-dir "$WORK_DIR"

rm -rf "$WORK_DIR"

echo "Generated Grammarinator generators in $GEN_DIR"
ls -la "$GEN_DIR"
```

- [ ] **Step 2: Run the script**

Run:
```bash
chmod +x legend-engine-fuzz/scripts/process_grammars.sh
legend-engine-fuzz/scripts/process_grammars.sh
```
Expected: Python generator files created in `fuzzer/fuzzer/generated/`

Note: This step may require adjustments if grammars have import chains that Grammarinator can't resolve. If so, manually flatten the `import` directives in the copies under `grammars/`. The originals in the engine are untouched.

- [ ] **Step 3: Commit**

```bash
git add legend-engine-fuzz/scripts/process_grammars.sh legend-engine-fuzz/fuzzer/fuzzer/generated/
git commit -m "feat(fuzz): process ANTLR grammars into Grammarinator generators"
```

---

### Task 6: Corpus Miner — Pure File Extractor

**Files:**
- Create: `legend-engine-fuzz/fuzzer/fuzzer/miner/pure_extractor.py`
- Create: `legend-engine-fuzz/fuzzer/tests/test_pure_extractor.py`

- [ ] **Step 1: Write the failing test**

```python
# tests/test_pure_extractor.py
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
    # Code without ### header is treated as Pure (default section)
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
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
cd legend-engine-fuzz/fuzzer && .venv/bin/pytest tests/test_pure_extractor.py -v
```
Expected: FAIL — `ModuleNotFoundError: No module named 'fuzzer.miner.pure_extractor'`

- [ ] **Step 3: Implement pure_extractor.py**

```python
# fuzzer/miner/pure_extractor.py
"""Extract Pure DSL sections from .pure files."""
import os
import re
from typing import List, Dict

SECTION_HEADER_RE = re.compile(r"^###(\w+)\s*$", re.MULTILINE)


def extract_sections(code: str, source: str = "<string>") -> List[Dict]:
    """Split Pure code into sections by ### headers.

    Returns a list of dicts: {"type": str, "code": str, "source": str}
    """
    matches = list(SECTION_HEADER_RE.finditer(code))

    if not matches:
        # No section header — treat entire code as Pure (default section)
        return [{"type": "Pure", "code": code.strip(), "source": source}]

    sections = []
    for i, match in enumerate(matches):
        section_type = match.group(1)
        start = match.end()
        end = matches[i + 1].start() if i + 1 < len(matches) else len(code)
        section_code = code[start:end].strip()
        if section_code:
            sections.append({
                "type": section_type,
                "code": section_code,
                "source": source,
            })
    return sections


def extract_sections_from_directory(directory: str) -> List[Dict]:
    """Extract sections from all .pure files in a directory tree."""
    all_sections = []
    for root, _dirs, files in os.walk(directory):
        for filename in sorted(files):
            if filename.endswith(".pure"):
                filepath = os.path.join(root, filename)
                with open(filepath, "r", encoding="utf-8", errors="replace") as f:
                    code = f.read()
                sections = extract_sections(code, source=filepath)
                all_sections.extend(sections)
    return all_sections
```

- [ ] **Step 4: Run the test to verify it passes**

Run:
```bash
cd legend-engine-fuzz/fuzzer && .venv/bin/pytest tests/test_pure_extractor.py -v
```
Expected: All 4 tests PASS

- [ ] **Step 5: Commit**

```bash
git add legend-engine-fuzz/fuzzer/fuzzer/miner/pure_extractor.py legend-engine-fuzz/fuzzer/tests/test_pure_extractor.py
git commit -m "feat(fuzz): implement Pure file section extractor with tests"
```

---

### Task 7: Corpus Miner — Java String Extractor

**Files:**
- Create: `legend-engine-fuzz/fuzzer/fuzzer/miner/java_extractor.py`
- Create: `legend-engine-fuzz/fuzzer/tests/test_java_extractor.py`

- [ ] **Step 1: Write the failing test**

```python
# tests/test_java_extractor.py
from fuzzer.miner.java_extractor import extract_pure_strings_from_java, extract_from_java_directory
import tempfile
import os


def test_extract_simple_string():
    java_code = '''
    @Test
    public void testMapping() {
        test("###Mapping\\n" +
             "Mapping test::mapping\\n" +
             "(\\n" +
             ")\\n");
    }
    '''
    strings = extract_pure_strings_from_java(java_code)
    assert len(strings) >= 1
    assert "###Mapping" in strings[0]
    assert "Mapping test::mapping" in strings[0]


def test_extract_multiline_concat():
    java_code = '''
    String code = "###Pure\\n" +
                  "Class test::Person\\n" +
                  "{\\n" +
                  "  name: String[1];\\n" +
                  "}\\n";
    '''
    strings = extract_pure_strings_from_java(java_code)
    assert len(strings) >= 1
    assert "Class test::Person" in strings[0]


def test_extract_from_directory():
    with tempfile.TemporaryDirectory() as tmpdir:
        with open(os.path.join(tmpdir, "TestGrammar.java"), "w") as f:
            f.write('''
            public class TestGrammar {
                @Test
                public void test1() {
                    test("###Pure\\nClass a::B {}\\n");
                }
            }
            ''')
        strings = extract_from_java_directory(tmpdir)
        assert len(strings) >= 1
        assert "###Pure" in strings[0]


def test_ignores_non_pure_strings():
    java_code = '''
    String x = "hello world";
    String y = "no section here";
    test("###Mapping\\nMapping a::b ()\\n");
    '''
    strings = extract_pure_strings_from_java(java_code)
    # Should only get the one with ###
    assert len(strings) == 1
    assert "###Mapping" in strings[0]
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
cd legend-engine-fuzz/fuzzer && .venv/bin/pytest tests/test_java_extractor.py -v
```
Expected: FAIL — `ModuleNotFoundError`

- [ ] **Step 3: Implement java_extractor.py**

```python
# fuzzer/miner/java_extractor.py
"""Extract Pure DSL code from Java test files (string concatenations)."""
import os
import re
from typing import List

# Match Java string literals: "..." possibly followed by + "..."
# This regex finds sequences of concatenated string literals
STRING_LITERAL_RE = re.compile(r'"((?:[^"\\]|\\.)*)"')

# Match a sequence of concatenated string literals: "..." + "..." + ...
CONCAT_RE = re.compile(
    r'"((?:[^"\\]|\\.)*)"'       # first string
    r'(?:\s*\+\s*\n?\s*'         # + with optional whitespace/newline
    r'"((?:[^"\\]|\\.)*)")*',    # subsequent strings
    re.MULTILINE
)


def _unescape_java_string(s: str) -> str:
    """Unescape Java string escape sequences."""
    s = s.replace("\\n", "\n")
    s = s.replace("\\t", "\t")
    s = s.replace("\\\\", "\\")
    s = s.replace('\\"', '"')
    s = s.replace("\\r", "\r")
    return s


def _reassemble_concat(text: str) -> str:
    """Find all string literals in a concatenation and join them."""
    parts = STRING_LITERAL_RE.findall(text)
    return _unescape_java_string("".join(parts))


def extract_pure_strings_from_java(java_code: str) -> List[str]:
    """Extract Pure DSL code strings from Java source code.

    Finds concatenated string literal sequences that contain ###SectionName
    markers, indicating they are Pure DSL code.
    """
    results = []
    # Find all regions of concatenated string literals
    # We look for sequences of "..." + "..." patterns
    concat_pattern = re.compile(
        r'("(?:[^"\\]|\\.)*"'           # first string literal
        r'(?:\s*\+\s*\n?\s*'            # + separator
        r'"(?:[^"\\]|\\.)*")*)',         # more string literals
        re.MULTILINE
    )

    for match in concat_pattern.finditer(java_code):
        assembled = _reassemble_concat(match.group(0))
        if "###" in assembled:
            results.append(assembled)

    return results


def extract_from_java_directory(directory: str, pattern: str = "Test*.java") -> List[str]:
    """Extract Pure DSL strings from all matching Java files in a directory tree."""
    import fnmatch
    all_strings = []
    for root, _dirs, files in os.walk(directory):
        for filename in sorted(files):
            if fnmatch.fnmatch(filename, pattern):
                filepath = os.path.join(root, filename)
                with open(filepath, "r", encoding="utf-8", errors="replace") as f:
                    java_code = f.read()
                strings = extract_pure_strings_from_java(java_code)
                all_strings.extend(strings)
    return all_strings
```

- [ ] **Step 4: Run the test to verify it passes**

Run:
```bash
cd legend-engine-fuzz/fuzzer && .venv/bin/pytest tests/test_java_extractor.py -v
```
Expected: All 4 tests PASS

- [ ] **Step 5: Commit**

```bash
git add legend-engine-fuzz/fuzzer/fuzzer/miner/java_extractor.py legend-engine-fuzz/fuzzer/tests/test_java_extractor.py
git commit -m "feat(fuzz): implement Java test string extractor with tests"
```

---

### Task 8: Corpus Miner — Run Extraction on Legend Engine

**Files:**
- Create: `legend-engine-fuzz/fuzzer/fuzzer/miner/__main__.py` (CLI for mining)

- [ ] **Step 1: Write the miner CLI**

```python
# fuzzer/miner/__main__.py
"""CLI to mine Pure DSL corpus from legend-engine source tree."""
import argparse
import json
import os
import sys

from .pure_extractor import extract_sections_from_directory, extract_sections
from .java_extractor import extract_from_java_directory


def main():
    parser = argparse.ArgumentParser(description="Mine Pure DSL corpus from legend-engine")
    parser.add_argument("engine_root", help="Path to legend-engine root directory")
    parser.add_argument("--output", "-o", default="corpus/raw", help="Output directory for extracted sections")
    args = parser.parse_args()

    os.makedirs(args.output, exist_ok=True)

    all_sections = []

    # Extract from .pure files
    print("Extracting from .pure files...")
    pure_sections = extract_sections_from_directory(args.engine_root)
    print(f"  Found {len(pure_sections)} sections from .pure files")
    all_sections.extend(pure_sections)

    # Extract from Java test files
    print("Extracting from Java test files...")
    java_strings = extract_from_java_directory(args.engine_root, pattern="Test*.java")
    for i, s in enumerate(java_strings):
        sections = extract_sections(s, source=f"java-string-{i}")
        all_sections.extend(sections)
    print(f"  Found {len(java_strings)} Java strings, {len(all_sections) - len(pure_sections)} additional sections")

    # Group by type and save
    by_type = {}
    for section in all_sections:
        t = section["type"]
        by_type.setdefault(t, []).append(section)

    for section_type, sections in sorted(by_type.items()):
        outfile = os.path.join(args.output, f"{section_type.lower()}_sections.jsonl")
        with open(outfile, "w") as f:
            for s in sections:
                f.write(json.dumps(s) + "\n")
        print(f"  Wrote {len(sections)} {section_type} sections to {outfile}")

    print(f"\nTotal: {len(all_sections)} sections across {len(by_type)} types")


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: Run the miner on legend-engine**

Run:
```bash
cd legend-engine-fuzz/fuzzer && .venv/bin/python -m fuzzer.miner ../../ -o ../corpus/raw
```
Expected: Output showing hundreds of extracted sections across Pure, Mapping, Relational, etc. types. JSONL files created in `corpus/raw/`.

- [ ] **Step 3: Inspect the output**

Run:
```bash
wc -l legend-engine-fuzz/corpus/raw/*.jsonl
head -1 legend-engine-fuzz/corpus/raw/pure_sections.jsonl | python3 -m json.tool
```
Expected: Line counts for each section type; valid JSON with `type`, `code`, `source` fields.

- [ ] **Step 4: Commit**

```bash
git add legend-engine-fuzz/fuzzer/fuzzer/miner/__main__.py legend-engine-fuzz/corpus/raw/
git commit -m "feat(fuzz): add corpus miner CLI and extract initial corpus from legend-engine"
```

---

### Task 9: Symbol Table

**Files:**
- Create: `legend-engine-fuzz/fuzzer/fuzzer/composer/symbol_table.py`
- Create: `legend-engine-fuzz/fuzzer/tests/test_symbol_table.py`

- [ ] **Step 1: Write the failing test**

```python
# tests/test_symbol_table.py
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
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
cd legend-engine-fuzz/fuzzer && .venv/bin/pytest tests/test_symbol_table.py -v
```
Expected: FAIL — `ModuleNotFoundError`

- [ ] **Step 3: Implement symbol_table.py**

```python
# fuzzer/composer/symbol_table.py
"""Shared symbol table for coordinated multi-section document generation."""
import random
from typing import List, Dict, Optional


class SymbolTable:
    """Tracks all generated names across sections for referential consistency."""

    def __init__(self):
        self._classes: Dict[str, Dict] = {}      # fqn -> {package, name, properties}
        self._enums: Dict[str, Dict] = {}         # fqn -> {package, name, values}
        self._associations: Dict[str, Dict] = {}  # fqn -> {package, name, ...}
        self._databases: Dict[str, Dict] = {}     # fqn -> {package, name, tables}
        self._tables: Dict[str, List[Dict]] = {}  # db_fqn -> [{schema, name, columns}]
        self._mappings: Dict[str, Dict] = {}      # fqn -> {package, name}

    # --- Classes ---

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

    # --- Enums ---

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

    # --- Databases ---

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

    # --- Mappings ---

    def add_mapping(self, package: str, name: str):
        fqn = f"{package}::{name}"
        self._mappings[fqn] = {"package": package, "name": name}

    def get_mapping_names(self) -> List[str]:
        return sorted(self._mappings.keys())
```

- [ ] **Step 4: Run the test to verify it passes**

Run:
```bash
cd legend-engine-fuzz/fuzzer && .venv/bin/pytest tests/test_symbol_table.py -v
```
Expected: All 5 tests PASS

- [ ] **Step 5: Commit**

```bash
git add legend-engine-fuzz/fuzzer/fuzzer/composer/symbol_table.py legend-engine-fuzz/fuzzer/tests/test_symbol_table.py
git commit -m "feat(fuzz): implement symbol table for cross-section name coordination"
```

---

### Task 10: Document Composer

**Files:**
- Create: `legend-engine-fuzz/fuzzer/fuzzer/composer/document_composer.py`
- Create: `legend-engine-fuzz/fuzzer/tests/test_document_composer.py`

- [ ] **Step 1: Write the failing test**

```python
# tests/test_document_composer.py
from fuzzer.composer.document_composer import DocumentComposer
from fuzzer.composer.symbol_table import SymbolTable


def test_compose_pure_section():
    composer = DocumentComposer()
    doc = composer.generate_pure_section(num_classes=2, num_enums=1)
    assert "###Pure" in doc
    assert "Class" in doc
    assert "Enum" in doc
    # Symbol table should be populated
    assert len(composer.symbol_table.get_class_names()) == 2
    assert len(composer.symbol_table.get_enum_names()) == 1


def test_compose_relational_section():
    composer = DocumentComposer()
    # First generate classes so the relational section has types to mirror
    composer.generate_pure_section(num_classes=1)
    doc = composer.generate_relational_section(num_databases=1)
    assert "###Relational" in doc
    assert "Database" in doc
    assert "Table" in doc
    assert len(composer.symbol_table.get_database_names()) == 1


def test_compose_m2m_mapping_section():
    composer = DocumentComposer()
    composer.generate_pure_section(num_classes=2)
    doc = composer.generate_m2m_mapping_section()
    assert "###Mapping" in doc
    assert "Mapping" in doc
    assert "Pure" in doc  # M2M uses Pure mapping type


def test_compose_full_document():
    composer = DocumentComposer()
    doc = composer.generate_full_document(
        num_classes=2,
        num_enums=1,
        num_databases=1,
        mapping_type="m2m",
    )
    assert "###Pure" in doc
    assert "###Mapping" in doc
    assert "Class" in doc


def test_compose_relational_mapping_document():
    composer = DocumentComposer()
    doc = composer.generate_full_document(
        num_classes=1,
        num_enums=0,
        num_databases=1,
        mapping_type="relational",
    )
    assert "###Pure" in doc
    assert "###Relational" in doc
    assert "###Mapping" in doc
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
cd legend-engine-fuzz/fuzzer && .venv/bin/pytest tests/test_document_composer.py -v
```
Expected: FAIL — `ModuleNotFoundError`

- [ ] **Step 3: Implement document_composer.py**

```python
# fuzzer/composer/document_composer.py
"""Orchestrates generation of multi-section Pure documents with consistent cross-references."""
import random
import string
from typing import List, Optional

from .symbol_table import SymbolTable

# Primitive types available in Pure
PURE_PRIMITIVE_TYPES = ["String", "Integer", "Float", "Boolean", "Date", "DateTime", "StrictDate"]

# SQL types corresponding to Pure types
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
    """Generate a random CamelCase identifier."""
    suffix = "".join(random.choices(string.ascii_lowercase, k=length))
    return prefix + suffix.capitalize()


def _random_package(depth: int = 2) -> str:
    """Generate a random package path like 'test::model'."""
    parts = [_random_name(length=4) for _ in range(depth)]
    return "::".join(parts)


class DocumentComposer:
    """Generates complete multi-section Pure documents with coordinated names."""

    def __init__(self, seed: Optional[int] = None):
        self.symbol_table = SymbolTable()
        if seed is not None:
            random.seed(seed)
        self._package = _random_package()

    def generate_pure_section(self, num_classes: int = 2, num_enums: int = 1) -> str:
        """Generate a ###Pure section with classes and enums."""
        lines = ["###Pure"]

        # Generate enums
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

        # Generate classes
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
        """Generate a ###Relational section with databases and tables mirroring classes."""
        lines = ["###Relational"]

        for _ in range(num_databases):
            db_name = _random_name("DB")
            db_package = self._package
            db_fqn = f"{db_package}::{db_name}"
            self.symbol_table.add_database(db_package, db_name)

            table_lines = []
            # Create a table for each known class
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
        """Generate a ###Mapping section with M2M (Pure instance) class mappings."""
        lines = ["###Mapping"]

        mapping_name = _random_name("Map")
        self.symbol_table.add_mapping(self._package, mapping_name)

        class_names = self.symbol_table.get_class_names()
        if len(class_names) < 2:
            # Need at least 2 classes for M2M
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

        # Map properties with matching types, or use simple expressions
        for tp in target_props:
            # Try to find a source property of same type
            matching = [sp for sp in source_props if sp["type"] == tp["type"]]
            if matching:
                src_prop = random.choice(matching)
                lines.append(f"    {tp['name']}: $src.{src_prop['name']},")
            else:
                # Use a default value expression
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

        # Remove trailing comma from last property mapping
        if lines[-1].endswith(","):
            lines[-1] = lines[-1][:-1]

        lines.append("  }")
        lines.append(")")
        return "\n".join(lines)

    def generate_relational_mapping_section(self) -> str:
        """Generate a ###Mapping section with relational class mappings."""
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
                # Find matching table
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
        """Generate a complete multi-section Pure document."""
        sections = []

        # 1. Pure section (always needed)
        sections.append(self.generate_pure_section(num_classes=num_classes, num_enums=num_enums))

        # 2. Relational section (if relational mapping or explicitly requested)
        if mapping_type == "relational" or num_databases > 0:
            sections.append(self.generate_relational_section(num_databases=num_databases))

        # 3. Mapping section
        if mapping_type == "m2m":
            sections.append(self.generate_m2m_mapping_section())
        elif mapping_type == "relational":
            sections.append(self.generate_relational_mapping_section())

        return "\n\n".join(sections)
```

- [ ] **Step 4: Run the test to verify it passes**

Run:
```bash
cd legend-engine-fuzz/fuzzer && .venv/bin/pytest tests/test_document_composer.py -v
```
Expected: All 5 tests PASS

- [ ] **Step 5: Commit**

```bash
git add legend-engine-fuzz/fuzzer/fuzzer/composer/document_composer.py legend-engine-fuzz/fuzzer/tests/test_document_composer.py
git commit -m "feat(fuzz): implement document composer with Pure, Relational, and Mapping generation"
```

---

### Task 11: Harness Client

**Files:**
- Create: `legend-engine-fuzz/fuzzer/fuzzer/harness_client.py`
- Create: `legend-engine-fuzz/fuzzer/tests/test_harness_client.py`

- [ ] **Step 1: Write the failing test**

```python
# tests/test_harness_client.py
from fuzzer.harness_client import HarnessClient
import json


def test_format_request():
    client = HarnessClient.__new__(HarnessClient)
    req = client._format_request("test-1", "###Pure\nClass a::B {}")
    parsed = json.loads(req)
    assert parsed["id"] == "test-1"
    assert parsed["code"] == "###Pure\nClass a::B {}"


def test_parse_response():
    client = HarnessClient.__new__(HarnessClient)
    resp = '{"id":"test-1","result":"COMPILE_OK","time_ms":42}'
    parsed = client._parse_response(resp)
    assert parsed["id"] == "test-1"
    assert parsed["result"] == "COMPILE_OK"
    assert parsed["time_ms"] == 42


def test_is_crash():
    client = HarnessClient.__new__(HarnessClient)
    assert client._is_crash({"result": "PARSE_CRASH"})
    assert client._is_crash({"result": "COMPILE_CRASH"})
    assert not client._is_crash({"result": "COMPILE_OK"})
    assert not client._is_crash({"result": "PARSE_ERROR"})
    assert not client._is_crash({"result": "COMPILE_ERROR"})
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
cd legend-engine-fuzz/fuzzer && .venv/bin/pytest tests/test_harness_client.py -v
```
Expected: FAIL — `ModuleNotFoundError`

- [ ] **Step 3: Implement harness_client.py**

```python
# fuzzer/harness_client.py
"""Client that communicates with the Java fuzz harness via subprocess stdin/stdout."""
import json
import os
import subprocess
from typing import Dict, Optional


class HarnessClient:
    """Manages a subprocess running the Java FuzzHarness, communicating via JSON lines."""

    def __init__(self, jar_path: str, timeout_ms: int = 10000, java_cmd: str = "java"):
        self._jar_path = jar_path
        self._timeout_ms = timeout_ms
        self._java_cmd = java_cmd
        self._process: Optional[subprocess.Popen] = None

    def start(self):
        """Start the Java harness subprocess."""
        cmd = [
            self._java_cmd, "-jar", self._jar_path,
            "--timeout", str(self._timeout_ms),
        ]
        self._process = subprocess.Popen(
            cmd,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            bufsize=1,  # line-buffered
        )

    def stop(self):
        """Stop the Java harness subprocess."""
        if self._process:
            self._process.stdin.close()
            self._process.wait(timeout=5)
            self._process = None

    def classify(self, input_id: str, code: str) -> Dict:
        """Send code to harness and get classification result."""
        if not self._process or self._process.poll() is not None:
            raise RuntimeError("Harness process is not running")

        request = self._format_request(input_id, code)
        self._process.stdin.write(request + "\n")
        self._process.stdin.flush()

        response_line = self._process.stdout.readline()
        if not response_line:
            raise RuntimeError("Harness process closed stdout unexpectedly")

        return self._parse_response(response_line)

    def _format_request(self, input_id: str, code: str) -> str:
        return json.dumps({"id": input_id, "code": code})

    def _parse_response(self, line: str) -> Dict:
        return json.loads(line.strip())

    def _is_crash(self, result: Dict) -> bool:
        return result.get("result", "").endswith("_CRASH")

    def __enter__(self):
        self.start()
        return self

    def __exit__(self, *args):
        self.stop()
```

- [ ] **Step 4: Run the test to verify it passes**

Run:
```bash
cd legend-engine-fuzz/fuzzer && .venv/bin/pytest tests/test_harness_client.py -v
```
Expected: All 3 tests PASS

- [ ] **Step 5: Commit**

```bash
git add legend-engine-fuzz/fuzzer/fuzzer/harness_client.py legend-engine-fuzz/fuzzer/tests/test_harness_client.py
git commit -m "feat(fuzz): implement harness client for Java subprocess communication"
```

---

### Task 12: Grammar Walk Strategy

**Files:**
- Create: `legend-engine-fuzz/fuzzer/fuzzer/strategies/grammar_walk.py`
- Create: `legend-engine-fuzz/fuzzer/tests/test_grammar_walk.py`

- [ ] **Step 1: Write the failing test**

```python
# tests/test_grammar_walk.py
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
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
cd legend-engine-fuzz/fuzzer && .venv/bin/pytest tests/test_grammar_walk.py -v
```
Expected: FAIL — `ModuleNotFoundError`

- [ ] **Step 3: Implement grammar_walk.py**

```python
# fuzzer/strategies/grammar_walk.py
"""Grammar walk fuzzing strategy: generates random DSL code via the document composer + mutations."""
import random
import re
from typing import List, Optional

from ..composer.document_composer import DocumentComposer

# Simple tokenizer: split on whitespace and punctuation boundaries
TOKEN_RE = re.compile(r"(\s+|[{}()\[\];:,.<>*=+\-/|@#~!]+|[^\s{}()\[\];:,.<>*=+\-/|@#~!]+)")


def _tokenize(code: str) -> List[str]:
    return TOKEN_RE.findall(code)


def _detokenize(tokens: List[str]) -> str:
    return "".join(tokens)


class GrammarWalkStrategy:
    """Generates Pure DSL code using the document composer, with optional mutations for parser fuzzing."""

    def __init__(self, seed: Optional[int] = None):
        self._seed = seed
        if seed is not None:
            random.seed(seed)

    def generate(self, section_type: str = "full_m2m") -> str:
        """Generate a random Pure DSL document or section."""
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
        """Apply a mutation to existing code for parser fuzz testing."""
        tokens = _tokenize(code)
        # Filter to non-whitespace tokens for mutation targets
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
        """Generate a batch of random documents."""
        return [self.generate(section_type=section_type) for _ in range(count)]
```

- [ ] **Step 4: Run the test to verify it passes**

Run:
```bash
cd legend-engine-fuzz/fuzzer && .venv/bin/pytest tests/test_grammar_walk.py -v
```
Expected: All 7 tests PASS

- [ ] **Step 5: Commit**

```bash
git add legend-engine-fuzz/fuzzer/fuzzer/strategies/grammar_walk.py legend-engine-fuzz/fuzzer/tests/test_grammar_walk.py
git commit -m "feat(fuzz): implement grammar walk fuzzing strategy with mutations"
```

---

### Task 13: Reporter

**Files:**
- Create: `legend-engine-fuzz/fuzzer/fuzzer/reporter.py`

- [ ] **Step 1: Implement reporter.py**

```python
# fuzzer/reporter.py
"""Collects and summarizes fuzzing results."""
import json
import os
from collections import Counter
from typing import Dict, List


class FuzzReporter:
    """Collects fuzzing results and generates summary reports."""

    def __init__(self, results_dir: str):
        self._results_dir = results_dir
        self._crashes_dir = os.path.join(results_dir, "crashes")
        self._candidates_dir = os.path.join(results_dir, "test_candidates")
        self._reports_dir = os.path.join(results_dir, "reports")
        os.makedirs(self._crashes_dir, exist_ok=True)
        os.makedirs(self._candidates_dir, exist_ok=True)
        os.makedirs(self._reports_dir, exist_ok=True)

        self._stats = Counter()
        self._results: List[Dict] = []

    def record(self, input_id: str, code: str, result: Dict):
        """Record a single fuzzing result."""
        self._stats[result.get("result", "UNKNOWN")] += 1
        self._results.append({"id": input_id, "result": result})

        classification = result.get("result", "")

        # Save crashes
        if classification.endswith("_CRASH"):
            crash_file = os.path.join(self._crashes_dir, f"{input_id}.json")
            with open(crash_file, "w") as f:
                json.dump({"id": input_id, "code": code, **result}, f, indent=2)

        # Save test candidates
        if classification == "COMPILE_OK":
            candidate_file = os.path.join(self._candidates_dir, f"{input_id}.pure")
            with open(candidate_file, "w") as f:
                f.write(code)

    def summary(self) -> Dict:
        """Return a summary of all results."""
        return {
            "total": sum(self._stats.values()),
            "breakdown": dict(self._stats),
            "crashes": self._stats.get("PARSE_CRASH", 0) + self._stats.get("COMPILE_CRASH", 0),
            "test_candidates": self._stats.get("COMPILE_OK", 0),
        }

    def write_report(self):
        """Write a JSON summary report."""
        report = self.summary()
        report_file = os.path.join(self._reports_dir, "summary.json")
        with open(report_file, "w") as f:
            json.dump(report, f, indent=2)
        return report_file

    def print_summary(self):
        """Print a human-readable summary to stdout."""
        s = self.summary()
        print(f"\n{'='*50}")
        print(f"Fuzzing Summary")
        print(f"{'='*50}")
        print(f"Total inputs:     {s['total']}")
        print(f"Crashes found:    {s['crashes']}")
        print(f"Test candidates:  {s['test_candidates']}")
        print(f"\nBreakdown:")
        for result_type, count in sorted(s["breakdown"].items()):
            print(f"  {result_type}: {count}")
        print(f"{'='*50}\n")
```

- [ ] **Step 2: Commit**

```bash
git add legend-engine-fuzz/fuzzer/fuzzer/reporter.py
git commit -m "feat(fuzz): implement fuzzing result reporter"
```

---

### Task 14: Main CLI Entrypoint

**Files:**
- Create: `legend-engine-fuzz/fuzz.py`

- [ ] **Step 1: Implement fuzz.py**

```python
#!/usr/bin/env python3
"""Main CLI entrypoint for the Legend Engine DSL fuzzer."""
import argparse
import os
import sys
import uuid

# Add fuzzer package to path
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "fuzzer"))

from fuzzer.strategies.grammar_walk import GrammarWalkStrategy
from fuzzer.harness_client import HarnessClient
from fuzzer.reporter import FuzzReporter


def find_harness_jar():
    """Find the harness JAR file."""
    candidates = [
        os.path.join(os.path.dirname(__file__), "harness", "target", "legend-engine-fuzz-harness-fuzz-SNAPSHOT.jar"),
    ]
    for c in candidates:
        if os.path.exists(c):
            return c
    return None


def run_grammar_walk(args):
    """Run the grammar-walk fuzzing strategy."""
    jar_path = args.jar or find_harness_jar()
    if not jar_path:
        print("ERROR: Could not find harness JAR. Build it first or pass --jar.", file=sys.stderr)
        sys.exit(1)

    strategy = GrammarWalkStrategy(seed=args.seed)
    reporter = FuzzReporter(args.results_dir)

    section_types = ["pure", "relational", "full_m2m", "full_relational"]
    mutation_types = ["delete_token", "swap_tokens", "duplicate_token", "inject_garbage"]

    print(f"Starting grammar-walk fuzzing with harness: {jar_path}")
    print(f"Generating {args.count} inputs...")

    with HarnessClient(jar_path, timeout_ms=args.timeout) as harness:
        for i in range(args.count):
            input_id = f"gw-{uuid.uuid4().hex[:8]}"

            # Alternate between clean generation and mutation
            if i % 3 == 0:
                # Clean generation
                section_type = section_types[i % len(section_types)]
                code = strategy.generate(section_type=section_type)
            else:
                # Generate then mutate
                section_type = section_types[i % len(section_types)]
                code = strategy.generate(section_type=section_type)
                mutation = mutation_types[i % len(mutation_types)]
                code = strategy.mutate(code, mutation_type=mutation)

            try:
                result = harness.classify(input_id, code)
                reporter.record(input_id, code, result)

                if result.get("result", "").endswith("_CRASH"):
                    print(f"  CRASH found: {input_id} — {result.get('exception', 'unknown')}")

                if (i + 1) % 100 == 0:
                    print(f"  Progress: {i + 1}/{args.count}")
            except Exception as e:
                print(f"  Error processing {input_id}: {e}", file=sys.stderr)

    reporter.print_summary()
    report_file = reporter.write_report()
    print(f"Report saved to: {report_file}")


def run_report(args):
    """Show a report of existing results."""
    reporter = FuzzReporter(args.results_dir)
    # Count existing crash files
    crashes_dir = os.path.join(args.results_dir, "crashes")
    candidates_dir = os.path.join(args.results_dir, "test_candidates")

    crash_count = len(os.listdir(crashes_dir)) if os.path.isdir(crashes_dir) else 0
    candidate_count = len(os.listdir(candidates_dir)) if os.path.isdir(candidates_dir) else 0

    print(f"Results in {args.results_dir}:")
    print(f"  Crashes:         {crash_count}")
    print(f"  Test candidates: {candidate_count}")


def main():
    parser = argparse.ArgumentParser(description="Legend Engine DSL Fuzzer")
    parser.add_argument("--results-dir", default="results", help="Directory for results")
    parser.add_argument("--jar", help="Path to harness JAR")
    parser.add_argument("--timeout", type=int, default=10000, help="Timeout per input in ms")
    parser.add_argument("--seed", type=int, default=None, help="Random seed")

    subparsers = parser.add_subparsers(dest="command")

    # grammar-walk
    gw = subparsers.add_parser("grammar-walk", help="Run grammar-walk fuzzing strategy")
    gw.add_argument("--count", type=int, default=1000, help="Number of inputs to generate")

    # report
    subparsers.add_parser("report", help="Show results report")

    args = parser.parse_args()

    if args.command == "grammar-walk":
        run_grammar_walk(args)
    elif args.command == "report":
        run_report(args)
    else:
        parser.print_help()


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: Make executable and test help**

Run:
```bash
chmod +x legend-engine-fuzz/fuzz.py
cd legend-engine-fuzz && python3 fuzz.py --help
```
Expected: Help text showing available subcommands

- [ ] **Step 3: Commit**

```bash
git add legend-engine-fuzz/fuzz.py
git commit -m "feat(fuzz): add main CLI entrypoint for fuzzer"
```

---

### Task 15: End-to-End Integration Test

**Files:** No new files — this task validates the full pipeline.

- [ ] **Step 1: Build the Java harness JAR**

Run:
```bash
cd legend-engine-fuzz/harness && mvn package -q -DskipTests
```
Expected: `target/legend-engine-fuzz-harness-fuzz-SNAPSHOT.jar` created

- [ ] **Step 2: Run a small fuzzing campaign**

Run:
```bash
cd legend-engine-fuzz && python3 fuzz.py grammar-walk --count 50 --seed 42 --results-dir results
```
Expected: Output showing progress, final summary with breakdown of COMPILE_OK, COMPILE_ERROR, PARSE_ERROR, and possibly some crashes. Test candidate `.pure` files saved in `results/test_candidates/`.

- [ ] **Step 3: Inspect a test candidate**

Run:
```bash
ls legend-engine-fuzz/results/test_candidates/ | head -5
cat legend-engine-fuzz/results/test_candidates/$(ls legend-engine-fuzz/results/test_candidates/ | head -1)
```
Expected: A valid-looking Pure program with `###Pure`, `Class`, and `###Mapping` sections.

- [ ] **Step 4: Inspect crashes if any**

Run:
```bash
ls legend-engine-fuzz/results/crashes/ 2>/dev/null | head -5
```
Expected: Either empty (no crashes found in 50 inputs) or JSON files with crash details.

- [ ] **Step 5: Run the report command**

Run:
```bash
cd legend-engine-fuzz && python3 fuzz.py report
```
Expected: Summary showing crash and test candidate counts.

- [ ] **Step 6: Commit results directory structure (not the generated files)**

```bash
echo "results/" >> legend-engine-fuzz/.gitignore
echo ".venv/" >> legend-engine-fuzz/.gitignore
echo "__pycache__/" >> legend-engine-fuzz/.gitignore
echo "*.pyc" >> legend-engine-fuzz/.gitignore
git add legend-engine-fuzz/.gitignore
git commit -m "feat(fuzz): add .gitignore and complete end-to-end integration"
```

---

### Task 16: Probabilistic Model Builder (Stretch)

**Files:**
- Create: `legend-engine-fuzz/fuzzer/fuzzer/miner/model_builder.py`
- Create: `legend-engine-fuzz/fuzzer/tests/test_model_builder.py`

This task enhances generation quality by mining token/structure frequency distributions from the corpus. It is buildable independently after the core pipeline works.

- [ ] **Step 1: Write the failing test**

```python
# tests/test_model_builder.py
from fuzzer.miner.model_builder import build_token_model, TokenModel


def test_build_token_model_from_sections():
    sections = [
        {"type": "Pure", "code": "Class test::Person { name: String[1]; age: Integer[1]; }", "source": "test"},
        {"type": "Pure", "code": "Class test::Firm { legalName: String[1]; }", "source": "test"},
    ]
    model = build_token_model(sections, section_type="Pure")
    # Should have learned identifier patterns
    assert model.total_tokens > 0
    # Should be able to sample an identifier
    ident = model.sample_identifier()
    assert isinstance(ident, str)
    assert len(ident) > 0


def test_build_token_model_type_distribution():
    sections = [
        {"type": "Pure", "code": "Class a::B { x: String[1]; y: String[1]; z: Integer[1]; }", "source": "test"},
    ]
    model = build_token_model(sections, section_type="Pure")
    dist = model.type_distribution
    # String should be more frequent than Integer
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
    # Should still return something reasonable
    ident = model.sample_identifier()
    assert isinstance(ident, str)
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
cd legend-engine-fuzz/fuzzer && .venv/bin/pytest tests/test_model_builder.py -v
```
Expected: FAIL — `ModuleNotFoundError`

- [ ] **Step 3: Implement model_builder.py**

```python
# fuzzer/miner/model_builder.py
"""Build probabilistic models from mined corpus sections."""
import random
import re
from collections import Counter
from typing import Dict, List, Optional

# Patterns to extract from Pure code
IDENTIFIER_RE = re.compile(r"\b([a-z][a-zA-Z0-9]*)\b")
TYPE_RE = re.compile(r":\s*(String|Integer|Float|Boolean|Date|DateTime|StrictDate|[A-Z][a-zA-Z0-9]*)")
MULTIPLICITY_RE = re.compile(r"\[([\d.*]+(?:\.\.\d+|\.\.\*)?)\]")
CLASS_NAME_RE = re.compile(r"Class\s+[\w:]+::(\w+)")
PROPERTY_NAME_RE = re.compile(r"^\s+(\w+)\s*:", re.MULTILINE)


class TokenModel:
    """A simple probabilistic model over token distributions from a corpus."""

    def __init__(self):
        self.identifiers: Counter = Counter()
        self.type_distribution: Counter = Counter()
        self.multiplicity_distribution: Counter = Counter()
        self.class_names: Counter = Counter()
        self.property_names: Counter = Counter()
        self.total_tokens: int = 0

    def sample_identifier(self) -> str:
        """Sample an identifier from the learned distribution, or generate a fallback."""
        if self.identifiers:
            return _weighted_choice(self.identifiers)
        return "value"

    def sample_type(self) -> str:
        """Sample a type from the learned distribution."""
        if self.type_distribution:
            return _weighted_choice(self.type_distribution)
        return "String"

    def sample_multiplicity(self) -> str:
        """Sample a multiplicity from the learned distribution."""
        if self.multiplicity_distribution:
            return "[" + _weighted_choice(self.multiplicity_distribution) + "]"
        return "[1]"

    def sample_class_name(self) -> str:
        """Sample a class name pattern."""
        if self.class_names:
            return _weighted_choice(self.class_names)
        return "Entity"

    def sample_property_name(self) -> str:
        """Sample a property name pattern."""
        if self.property_names:
            return _weighted_choice(self.property_names)
        return "value"

    def to_dict(self) -> Dict:
        """Serialize the model to a dict."""
        return {
            "total_tokens": self.total_tokens,
            "identifiers": dict(self.identifiers.most_common(200)),
            "type_distribution": dict(self.type_distribution),
            "multiplicity_distribution": dict(self.multiplicity_distribution),
            "class_names": dict(self.class_names.most_common(100)),
            "property_names": dict(self.property_names.most_common(200)),
        }


def _weighted_choice(counter: Counter) -> str:
    """Pick a random item weighted by count."""
    items = list(counter.keys())
    weights = list(counter.values())
    return random.choices(items, weights=weights, k=1)[0]


def build_token_model(sections: List[Dict], section_type: str) -> TokenModel:
    """Build a TokenModel from corpus sections of a given type."""
    model = TokenModel()

    for section in sections:
        if section.get("type") != section_type:
            continue
        code = section.get("code", "")

        # Extract identifiers
        for m in IDENTIFIER_RE.finditer(code):
            model.identifiers[m.group(1)] += 1
            model.total_tokens += 1

        # Extract types
        for m in TYPE_RE.finditer(code):
            model.type_distribution[m.group(1)] += 1

        # Extract multiplicities
        for m in MULTIPLICITY_RE.finditer(code):
            model.multiplicity_distribution[m.group(1)] += 1

        # Extract class names
        for m in CLASS_NAME_RE.finditer(code):
            model.class_names[m.group(1)] += 1

        # Extract property names
        for m in PROPERTY_NAME_RE.finditer(code):
            model.property_names[m.group(1)] += 1

    return model
```

- [ ] **Step 4: Run the test to verify it passes**

Run:
```bash
cd legend-engine-fuzz/fuzzer && .venv/bin/pytest tests/test_model_builder.py -v
```
Expected: All 4 tests PASS

- [ ] **Step 5: Commit**

```bash
git add legend-engine-fuzz/fuzzer/fuzzer/miner/model_builder.py legend-engine-fuzz/fuzzer/tests/test_model_builder.py
git commit -m "feat(fuzz): implement probabilistic token model builder from mined corpus"
```

---

## Task Dependency Summary

```
Task 1  (Java pom.xml)
  └─> Task 2  (FuzzHarness.java)

Task 3  (Python project setup)
  ├─> Task 4  (grammar collection)
  │     └─> Task 5  (grammar processing)
  ├─> Task 6  (pure extractor)
  ├─> Task 7  (java extractor)
  │     └─> Task 8  (run extraction)
  │           └─> Task 16 (model builder - stretch)
  ├─> Task 9  (symbol table)
  │     └─> Task 10 (document composer)
  │           └─> Task 12 (grammar walk strategy)
  ├─> Task 11 (harness client)
  └─> Task 13 (reporter)

Tasks 2, 12, 11, 13 ──> Task 14 (CLI entrypoint)
Task 14 ──> Task 15 (end-to-end test)
```

Tasks without dependencies on each other can be built in parallel:
- Tasks 1-2 (Java) and Tasks 3-13 (Python) are independent until Task 15
- Within Python: Tasks 6, 7, 9, 11, 13 are independent of each other
