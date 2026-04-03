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

# Process Domain grammar (classes, enums -- the ###Pure section)
grammarinator-process "$WORK_DIR/DomainLexerGrammar.g4" "$WORK_DIR/DomainParserGrammar.g4" \
    -o "$GEN_DIR" \
    --lib "$WORK_DIR"

# Process Mapping grammar
grammarinator-process "$WORK_DIR/MappingLexerGrammar.g4" "$WORK_DIR/MappingParserGrammar.g4" \
    -o "$GEN_DIR" \
    --lib "$WORK_DIR"

# Process PureInstanceClassMapping grammar (M2M)
grammarinator-process "$WORK_DIR/PureInstanceClassMappingLexerGrammar.g4" "$WORK_DIR/PureInstanceClassMappingParserGrammar.g4" \
    -o "$GEN_DIR" \
    --lib "$WORK_DIR"

# Process Relational grammar
grammarinator-process "$WORK_DIR/RelationalLexerGrammar.g4" "$WORK_DIR/RelationalParserGrammar.g4" \
    -o "$GEN_DIR" \
    --lib "$WORK_DIR"

rm -rf "$WORK_DIR"

echo "Generated Grammarinator generators in $GEN_DIR"
ls -la "$GEN_DIR"
