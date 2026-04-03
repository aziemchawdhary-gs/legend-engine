#!/bin/bash
# Collects .g4 grammar files from legend-engine into grouped directories for Grammarinator
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
FUZZ_DIR="$(dirname "$SCRIPT_DIR")"
ENGINE_DIR="$FUZZ_DIR"

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
