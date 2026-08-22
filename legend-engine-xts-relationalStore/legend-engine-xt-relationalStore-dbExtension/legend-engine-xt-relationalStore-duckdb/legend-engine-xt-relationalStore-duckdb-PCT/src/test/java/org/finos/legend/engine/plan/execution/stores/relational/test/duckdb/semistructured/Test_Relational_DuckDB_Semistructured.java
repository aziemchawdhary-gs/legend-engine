// Copyright 2026 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.finos.legend.engine.plan.execution.stores.relational.test.duckdb.semistructured;

import static org.finos.legend.engine.test.shared.framework.PureTestHelperFramework.wrapSuite;
import java.util.Map;
import junit.framework.Test;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.MutableMap;
import org.eclipse.collections.impl.tuple.Tuples;
import org.finos.legend.engine.plan.execution.stores.relational.connection.tests.api.TestConnectionIntegrationLoader;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.relational.connection.DatabaseType;
import org.finos.legend.engine.test.shared.framework.TestServerResource;
import org.finos.legend.pure.m3.execution.test.PureTestBuilder;
import org.finos.legend.pure.m3.execution.test.TestCollection;
import org.finos.legend.pure.m4.coreinstance.CoreInstance;
import org.finos.legend.pure.runtime.java.compiled.execution.CompiledExecutionSupport;
import org.finos.legend.pure.runtime.java.compiled.testHelper.PureTestBuilderCompiled;

// TODO move to a better module!
//
// Phase-0 baseline (see docs/superpowers/plans/2026-08-17-m2m-sql-execution-phase1): DuckDB's
// semi-structured dialect support (core_relational_duckdb/relational/sqlQueryToString/duckdbExtension.pure)
// only implements the plain extractFromSemiStructured path today. Running the shared
// meta::relational::tests::semistructured corpus against it (unlike H2/Databricks/Snowflake, this
// corpus had never been wired up for DuckDB) surfaces ~148 of 151 tests failing. Each falls into one
// of a small number of root-cause buckets, tracked below by the shared substring asserted in
// pathToReason. None of these are one-line dialect fixes (see docs/pct/expected-failures-howto.md for
// the qualifier taxonomy) -- they are missing feature support, tracked here as a baseline for the
// later phases of the M2M SQL execution work to close incrementally:
//   - "Match failure: SemiStructur..." (42 tests): explode() is not implemented at all in the DuckDB
//     SQL generator -- sqlQueryToString falls through to the catch-all pattern match failure.
//   - "No function matches the given name and argument types 'json_extract(JSON, BOOLEAN)'" (28 tests):
//     boolean-typed filter/match comparisons against a semi-structured column are not coerced before
//     being expressed as SQL, so DuckDB rejects json_extract's second argument.
//   - "failed to bind function, ... '"filter"" / "'reduce(" / "'apply(" (12 tests): the array
//     filter/fold/sub-aggregation lambda builders emit DuckDB-incompatible function calls.
//   - "actual: '...'" (57 tests): assertion mismatches. The dominant pattern here (visible in the
//     "actual" values) is that extracted string values keep their embedded JSON quoting (round-trips
//     through the CSV test-result comparison as e.g. '"""Firm X"""' for what should be 'Firm X'), plus
//     several outright null/missing values on nested array access. This looks like a single systemic
//     quoting/typing bug in the semi-structured extraction path, but it spans many different query
//     shapes (plain extraction, joins, group-by, unions, sub-classes) so root-causing and fixing it is
//     out of scope for this phase-0 baseline task -- flagged as a concern in the task report.
//   - "Row 2 has too many columns" (4 tests) / "JSON path error near" (3 tests) / "Malformed JSON..."
//     (2 tests): array-element and dotted-path navigation edge cases not handled by the DuckDB JSON
//     path builder.
//
// The one known small dialect bug called out in the task brief -- a missing space in the INTEGER
// branch of processExtractFromSemiStructuredParamsFoDuckDb (duckdbExtension.pure:360, "cast(...as
// INTEGER)") -- was checked against this run's failures and does not manifest: DuckDB's SQL tokenizer
// does not require whitespace between a closing paren and a keyword, so no fix was made.
public class Test_Relational_DuckDB_Semistructured
{
    public static Test suite()
    {
        String testPackage = "meta::relational::tests::pct::duckDB::semistructured";
        CompiledExecutionSupport executionSupport = PureTestBuilderCompiled.getClassLoaderExecutionSupport();

        MutableMap<String, String> pathToReason = Maps.mutable.<String, String>empty()
                .withKeyValue(
                        "meta::relational::tests::semistructured::composition::testEmbeddedMappingSemiStructuredAccess_Connection_1__Boolean_1_",
                        "actual:   'Id,InstanceId,InstanceName")
                .withKeyValue(
                        "meta::relational::tests::semistructured::composition::testSemiStructuredThenDerivedPrimitivePropertyAccess_Connection_1__Boolean_1_",
                        "actual:   'Id,Trade Name,Unique Ref,Display Name")
                .withKeyValue(
                        "meta::relational::tests::semistructured::explode::testAggregationAggregateExplodedPropertyInsideProject_Connection_1__Boolean_1_",
                        "Match failure: SemiStructur")
                .withKeyValue(
                        "meta::relational::tests::semistructured::explode::testAggregationAggregateExplodedPropertyUsingGroupBy_Connection_1__Boolean_1_",
                        "Match failure: SemiStructur")
                .withKeyValue(
                        "meta::relational::tests::semistructured::explode::testCanProperlyMixExtractAndExplode_Connection_1__Boolean_1_",
                        "Match failure: SemiStructur")
                .withKeyValue(
                        "meta::relational::tests::semistructured::explode::testComplexProjectFlattenedAndExplodedPropertiesInProject_Connection_1__Boolean_1_",
                        "Match failure: SemiStructur")
                .withKeyValue(
                        "meta::relational::tests::semistructured::explode::testComplexProjectMultiplePropertiesToExplodeInProject_Connection_1__Boolean_1_",
                        "Match failure: SemiStructur")
                .withKeyValue(
                        "meta::relational::tests::semistructured::explode::testFilterOnExplodedPropertyFilteringInsideProject_Connection_1__Boolean_1_",
                        "Match failure: SemiStructur")
                .withKeyValue(
                        "meta::relational::tests::semistructured::explode::testJoinChainMultipleJoinsMultipleExplode_Connection_1__Boolean_1_",
                        "Match failure: SemiStructur")
                .withKeyValue(
                        "meta::relational::tests::semistructured::explode::testJoinChainMultipleJoinsSingleExplode_Connection_1__Boolean_1_",
                        "Match failure: SemiStructur")
                .withKeyValue(
                        "meta::relational::tests::semistructured::explode::testProjectWithExplodedPropertyAccessOnlyInFilter_Connection_1__Boolean_1_",
                        "Match failure: SemiStructur")
                .withKeyValue(
                        "meta::relational::tests::semistructured::explode::testSimpleJoinChainOneJoin_Connection_1__Boolean_1_",
                        "Match failure: SemiStructur")
                .withKeyValue(
                        "meta::relational::tests::semistructured::explode::testSimplePrimitivePropertiesProjectExplodeSource_Connection_1__Boolean_1_",
                        "Match failure: SemiStructur")
                .withKeyValue(
                        "meta::relational::tests::semistructured::explode::testSimplePrimitivePropertiesProjectExplodeTarget_Connection_1__Boolean_1_",
                        "Match failure: SemiStructur")
                .withKeyValue(
                        "meta::relational::tests::semistructured::explode::testSimplePrimitivePropertiesProjectWithFilterOnSource_Connection_1__Boolean_1_",
                        "Match failure: SemiStructur")
                .withKeyValue(
                        "meta::relational::tests::semistructured::explode::testSimplePrimitivePropertiesProjectWithFilterOnTarget_Connection_1__Boolean_1_",
                        "Match failure: SemiStructur")
                .withKeyValue(
                        "meta::relational::tests::semistructured::extract::testAllDataTypesAccess_Connection_1__Boolean_1_",
                        "JSON path error near")
                .withKeyValue(
                        "meta::relational::tests::semistructured::extract::testArrayElementNoFlattenAccess_Connection_1__Boolean_1_",
                        "JSON path error near")
                .withKeyValue(
                        "meta::relational::tests::semistructured::extract::testDotAndBracketNotationAccess_Connection_1__Boolean_1_",
                        "JSON path error near")
                .withKeyValue(
                        "meta::relational::tests::semistructured::flattening::relationFunctionMapping::testSemiStructuredComplexPropertyArrayIndexingFollowedBySubType_Connection_1__Boolean_1_",
                        "actual:   'First Name,Firm Name,Firm Address 0 Line 0 Line No")
                .withKeyValue(
                        "meta::relational::tests::semistructured::flattening::relationFunctionMapping::testSemiStructuredComplexPropertyArrayIndexing_Connection_1__Boolean_1_",
                        "actual:   'First Name,Firm Name,Firm Address 0 Name,Firm Address 2 Name")
                .withKeyValue(
                        "meta::relational::tests::semistructured::flattening::relationFunctionMapping::testSemiStructuredComplexPropertyFlatteningFollowedBySubType_Connection_1__Boolean_1_",
                        "Match failure: SemiStructur")
                .withKeyValue(
                        "meta::relational::tests::semistructured::flattening::relationFunctionMapping::testSemiStructuredComplexPropertyFlattening_Connection_1__Boolean_1_",
                        "Match failure: SemiStructur")
                .withKeyValue(
                        "meta::relational::tests::semistructured::flattening::relationFunctionMapping::testSemiStructuredMultiFlatten_Connection_1__Boolean_1_",
                        "Match failure: SemiStructur")
                .withKeyValue(
                        "meta::relational::tests::semistructured::flattening::relationFunctionMapping::testSemiStructuredMultiLevelFlattenMerging_Connection_1__Boolean_1_",
                        "Match failure: SemiStructur")
                .withKeyValue(
                        "meta::relational::tests::semistructured::flattening::relationFunctionMapping::testSemiStructuredMultiLevelFlatten_Connection_1__Boolean_1_",
                        "Match failure: SemiStructur")
                .withKeyValue(
                        "meta::relational::tests::semistructured::flattening::relationFunctionMapping::testSemiStructuredPrimitivePropertyArrayIndexing_Connection_1__Boolean_1_",
                        "Row 2 has too many columns")
                .withKeyValue(
                        "meta::relational::tests::semistructured::flattening::relationFunctionMapping::testSemiStructuredPrimitivePropertyFiltering_Connection_1__Boolean_1_",
                        "Match failure: SemiStructur")
                .withKeyValue(
                        "meta::relational::tests::semistructured::flattening::relationFunctionMapping::testSemiStructuredPrimitivePropertyFlattening_Connection_1__Boolean_1_",
                        "Match failure: SemiStructur")
                .withKeyValue(
                        "meta::relational::tests::semistructured::flattening::testMultiArrayOlapWithNestedIfExists_Connection_1__Boolean_1_",
                        "Match failure: SemiStructur")
                .withKeyValue(
                        "meta::relational::tests::semistructured::flattening::testSemiStructuredArrayDirectAt_Connection_1__Boolean_1_",
                        "actual:   'First Name,Firm Name,First Address Name")
                .withKeyValue(
                        "meta::relational::tests::semistructured::flattening::testSemiStructuredArrayDirectFold_Connection_1__Boolean_1_",
                        "No function matches the given name and argument types: 'reduce(")
                .withKeyValue(
                        "meta::relational::tests::semistructured::flattening::testSemiStructuredArrayDirectIsEmpty_Connection_1__Boolean_1_",
                        "actual:   'First Name,Firm Name,Has No Addresses")
                .withKeyValue(
                        "meta::relational::tests::semistructured::flattening::testSemiStructuredArrayDirectIsNotEmpty_Connection_1__Boolean_1_",
                        "actual:   'First Name,Firm Name,Has Addresses")
                .withKeyValue(
                        "meta::relational::tests::semistructured::flattening::testSemiStructuredArrayDirectSize_Connection_1__Boolean_1_",
                        "actual:   'First Name,Firm Name,Address Count")
                .withKeyValue(
                        "meta::relational::tests::semistructured::flattening::testSemiStructuredArrayFilterAtIndex_Connection_1__Boolean_1_",
                        "failed to bind function, either: No function matches the given name and argument types: '\"filter\"")
                .withKeyValue(
                        "meta::relational::tests::semistructured::flattening::testSemiStructuredArrayFilterFirstInIfElse_Connection_1__Boolean_1_",
                        "failed to bind function, either: No function matches the given name and argument types: '\"filter\"")
                .withKeyValue(
                        "meta::relational::tests::semistructured::flattening::testSemiStructuredArrayFilterFirstJoinStrings_Connection_1__Boolean_1_",
                        "failed to bind function, either: No function matches the given name and argument types: '\"filter\"")
                .withKeyValue(
                        "meta::relational::tests::semistructured::flattening::testSemiStructuredArrayFilterFirstWithEnumComparison_Connection_1__Boolean_1_",
                        "failed to bind function, either: No function matches the given name and argument types: '\"filter\"")
                .withKeyValue(
                        "meta::relational::tests::semistructured::flattening::testSemiStructuredArrayFilterFirst_Connection_1__Boolean_1_",
                        "failed to bind function, either: No function matches the given name and argument types: '\"filter\"")
                .withKeyValue(
                        "meta::relational::tests::semistructured::flattening::testSemiStructuredArrayFilterFold_Connection_1__Boolean_1_",
                        "failed to bind function, either: No function matches the given name and argument types: '\"filter\"")
                .withKeyValue(
                        "meta::relational::tests::semistructured::flattening::testSemiStructuredArrayFilterJoinStringsPrefixSuffix_Connection_1__Boolean_1_",
                        "failed to bind function, either: No function matches the given name and argument types: '\"filter\"")
                .withKeyValue(
                        "meta::relational::tests::semistructured::flattening::testSemiStructuredArrayFilterJoinStrings_Connection_1__Boolean_1_",
                        "failed to bind function, either: No function matches the given name and argument types: '\"filter\"")
                .withKeyValue(
                        "meta::relational::tests::semistructured::flattening::testSemiStructuredArrayFilterMap_Connection_1__Boolean_1_",
                        "Match failure: SemiStructur")
                .withKeyValue(
                        "meta::relational::tests::semistructured::flattening::testSemiStructuredArrayFilterOnly_Connection_1__Boolean_1_",
                        "Match failure: SemiStructur")
                .withKeyValue(
                        "meta::relational::tests::semistructured::flattening::testSemiStructuredArrayFilterSize_Connection_1__Boolean_1_",
                        "failed to bind function, either: No function matches the given name and argument types: '\"filter\"")
                .withKeyValue(
                        "meta::relational::tests::semistructured::flattening::testSemiStructuredArrayFilterWithIsEmpty_Connection_1__Boolean_1_",
                        "failed to bind function, either: No function matches the given name and argument types: '\"filter\"")
                .withKeyValue(
                        "meta::relational::tests::semistructured::flattening::testSemiStructuredComplexPropertyArrayIndexingFollowedBySubType_Connection_1__Boolean_1_",
                        "actual:   'First Name,Firm Name,Firm Address 0 Line 0 Line No")
                .withKeyValue(
                        "meta::relational::tests::semistructured::flattening::testSemiStructuredComplexPropertyArrayIndexing_Connection_1__Boolean_1_",
                        "actual:   'First Name,Firm Name,Firm Address 0 Name,Firm Address 2 Name")
                .withKeyValue(
                        "meta::relational::tests::semistructured::flattening::testSemiStructuredComplexPropertyFlatteningFollowedBySubType_Connection_1__Boolean_1_",
                        "Match failure: SemiStructur")
                .withKeyValue(
                        "meta::relational::tests::semistructured::flattening::testSemiStructuredComplexPropertyFlattening_Connection_1__Boolean_1_",
                        "Match failure: SemiStructur")
                .withKeyValue(
                        "meta::relational::tests::semistructured::flattening::testSemiStructuredMultiFlatten_Connection_1__Boolean_1_",
                        "Match failure: SemiStructur")
                .withKeyValue(
                        "meta::relational::tests::semistructured::flattening::testSemiStructuredMultiLevelFlattenMerging_Connection_1__Boolean_1_",
                        "Match failure: SemiStructur")
                .withKeyValue(
                        "meta::relational::tests::semistructured::flattening::testSemiStructuredMultiLevelFlatten_Connection_1__Boolean_1_",
                        "Match failure: SemiStructur")
                .withKeyValue(
                        "meta::relational::tests::semistructured::flattening::testSemiStructuredPrimitiveArrayConcatenate_Connection_1__Boolean_1_",
                        "Match failure: SemiStructur")
                .withKeyValue(
                        "meta::relational::tests::semistructured::flattening::testSemiStructuredPrimitiveArrayGroupByAggJoin_Connection_1__Boolean_1_",
                        "Match failure: SemiStructur")
                .withKeyValue(
                        "meta::relational::tests::semistructured::flattening::testSemiStructuredPrimitiveArrayInJoinWithFilter_Connection_1__Boolean_1_",
                        "Match failure: SemiStructur")
                .withKeyValue(
                        "meta::relational::tests::semistructured::flattening::testSemiStructuredPrimitiveArrayWithReplace_Connection_1__Boolean_1_",
                        "Match failure: SemiStructur")
                .withKeyValue(
                        "meta::relational::tests::semistructured::flattening::testSemiStructuredPrimitivePropertyArrayIndexing_Connection_1__Boolean_1_",
                        "Row 2 has too many columns")
                .withKeyValue(
                        "meta::relational::tests::semistructured::flattening::testSemiStructuredPrimitivePropertyFiltering_Connection_1__Boolean_1_",
                        "Match failure: SemiStructur")
                .withKeyValue(
                        "meta::relational::tests::semistructured::flattening::testSemiStructuredPrimitivePropertyFlattening_Connection_1__Boolean_1_",
                        "Match failure: SemiStructur")
                .withKeyValue(
                        "meta::relational::tests::semistructured::flattening::testSemiStructuredSubAggregationDeep_Connection_1__Boolean_1_",
                        "Match failure: SemiStructur")
                .withKeyValue(
                        "meta::relational::tests::semistructured::flattening::testSemiStructuredSubAggregation_Connection_1__Boolean_1_",
                        "No function matches the given name and argument types: 'apply(")
                .withKeyValue(
                        "meta::relational::tests::semistructured::inheritance::relationFunctionMapping::testSemiStructuredPropertyAccessAtBaseClass_Connection_1__Boolean_1_",
                        "actual:   'First Name,Firm Address Name")
                .withKeyValue(
                        "meta::relational::tests::semistructured::inheritance::relationFunctionMapping::testSemiStructuredPropertyAccessAtSubClassNestedUsingProjectWithFunctions_Connection_1__Boolean_1_",
                        "actual:   'First Name,Firm Address 0 Line No,Firm Address Street,Firm A")
                .withKeyValue(
                        "meta::relational::tests::semistructured::inheritance::relationFunctionMapping::testSemiStructuredPropertyAccessAtSubClassNested_Connection_1__Boolean_1_",
                        "actual:   'First Name,Firm Address 0 Line No,Firm Address Street,Firm A")
                .withKeyValue(
                        "meta::relational::tests::semistructured::inheritance::relationFunctionMapping::testSemiStructuredPropertyAccessAtSubClass_Connection_1__Boolean_1_",
                        "actual:   'First Name,Firm Address 0 Line No")
                .withKeyValue(
                        "meta::relational::tests::semistructured::inheritance::testSemiStructuredPropertyAccessAtBaseClass_Connection_1__Boolean_1_",
                        "actual:   'First Name,Firm Address Name")
                .withKeyValue(
                        "meta::relational::tests::semistructured::inheritance::testSemiStructuredPropertyAccessAtSubClassNestedUsingProjectWithFunctions_Connection_1__Boolean_1_",
                        "actual:   'First Name,Firm Address 0 Line No,Firm Address Street,Firm A")
                .withKeyValue(
                        "meta::relational::tests::semistructured::inheritance::testSemiStructuredPropertyAccessAtSubClassNested_Connection_1__Boolean_1_",
                        "actual:   'First Name,Firm Address 0 Line No,Firm Address Street,Firm A")
                .withKeyValue(
                        "meta::relational::tests::semistructured::inheritance::testSemiStructuredPropertyAccessAtSubClass_Connection_1__Boolean_1_",
                        "actual:   'First Name,Firm Address 0 Line No")
                .withKeyValue(
                        "meta::relational::tests::semistructured::join::testJoinOnSemiStructuredPropertyWithQPFilter_Connection_1__Boolean_1_",
                        "Match failure: SemiStructur")
                .withKeyValue(
                        "meta::relational::tests::semistructured::join::testJoinOnSemiStructuredProperty_Connection_1__Boolean_1_",
                        "actual:   'First Name,Last Name,Firm/Legal Name")
                .withKeyValue(
                        "meta::relational::tests::semistructured::match::relationFunctionMapping::testSemiStructuredMatchComplexProperty_Connection_1__Boolean_1_",
                        "No function matches the given name and argument types 'json_extract(JSON, BOOLEAN)'")
                .withKeyValue(
                        "meta::relational::tests::semistructured::match::relationFunctionMapping::testSemiStructuredMatchMultilevel_Connection_1__Boolean_1_",
                        "No function matches the given name and argument types 'json_extract(JSON, BOOLEAN)'")
                .withKeyValue(
                        "meta::relational::tests::semistructured::match::relationFunctionMapping::testSemiStructuredMatchWithComplexFilter_Connection_1__Boolean_1_",
                        "No function matches the given name and argument types 'json_extract(JSON, BOOLEAN)'")
                .withKeyValue(
                        "meta::relational::tests::semistructured::match::relationFunctionMapping::testSemiStructuredMatchWithDifferentMultiplicities_Connection_1__Boolean_1_",
                        "No function matches the given name and argument types 'json_extract(JSON, BOOLEAN)'")
                .withKeyValue(
                        "meta::relational::tests::semistructured::match::relationFunctionMapping::testSemiStructuredMatchWithMultipleProjectUsingCol_Connection_1__Boolean_1_",
                        "No function matches the given name and argument types 'json_extract(JSON, BOOLEAN)'")
                .withKeyValue(
                        "meta::relational::tests::semistructured::match::relationFunctionMapping::testSemiStructuredMatchWithMultipleProject_Connection_1__Boolean_1_",
                        "No function matches the given name and argument types 'json_extract(JSON, BOOLEAN)'")
                .withKeyValue(
                        "meta::relational::tests::semistructured::match::relationFunctionMapping::testSemiStructuredMatchWithVariableAccess_Connection_1__Boolean_1_",
                        "No function matches the given name and argument types 'json_extract(JSON, BOOLEAN)'")
                .withKeyValue(
                        "meta::relational::tests::semistructured::match::testSemiStructuredMatchComplexProperty_Connection_1__Boolean_1_",
                        "No function matches the given name and argument types 'json_extract(JSON, BOOLEAN)'")
                .withKeyValue(
                        "meta::relational::tests::semistructured::match::testSemiStructuredMatchMultilevel_Connection_1__Boolean_1_",
                        "No function matches the given name and argument types 'json_extract(JSON, BOOLEAN)'")
                .withKeyValue(
                        "meta::relational::tests::semistructured::match::testSemiStructuredMatchWithComplexFilter_Connection_1__Boolean_1_",
                        "No function matches the given name and argument types 'json_extract(JSON, BOOLEAN)'")
                .withKeyValue(
                        "meta::relational::tests::semistructured::match::testSemiStructuredMatchWithDifferentMultiplicities_Connection_1__Boolean_1_",
                        "No function matches the given name and argument types 'json_extract(JSON, BOOLEAN)'")
                .withKeyValue(
                        "meta::relational::tests::semistructured::match::testSemiStructuredMatchWithMultipleProjectUsingCol_Connection_1__Boolean_1_",
                        "No function matches the given name and argument types 'json_extract(JSON, BOOLEAN)'")
                .withKeyValue(
                        "meta::relational::tests::semistructured::match::testSemiStructuredMatchWithMultipleProject_Connection_1__Boolean_1_",
                        "No function matches the given name and argument types 'json_extract(JSON, BOOLEAN)'")
                .withKeyValue(
                        "meta::relational::tests::semistructured::match::testSemiStructuredMatchWithVariableAccess_Connection_1__Boolean_1_",
                        "No function matches the given name and argument types 'json_extract(JSON, BOOLEAN)'")
                .withKeyValue(
                        "meta::relational::tests::semistructured::multiBinding::relationFunctionMapping::testSemiStructuredPropertyAccessFromMultipleBindingMapping_Connection_1__Boolean_1_",
                        "actual:   'First Name,Firm Legal Name,Address Name")
                .withKeyValue(
                        "meta::relational::tests::semistructured::multiBinding::testSemiStructuredPropertyAccessFromMultipleBindingMapping_Connection_1__Boolean_1_",
                        "actual:   'First Name,Firm Legal Name,Address Name")
                .withKeyValue(
                        "meta::relational::tests::semistructured::multiBinding::testSemiStructuredPropertyAccessFromSingleBindingMapping_Connection_1__Boolean_1_",
                        "actual:   'First Name,Address Name")
                .withKeyValue(
                        "meta::relational::tests::semistructured::multiBinding::testSemiStructuredRelOpWithJoinPropertyAccessFromMultipleBindingMapping_Connection_1__Boolean_1_",
                        "actual:   'First Name,Firm Legal Name,Address Name,Manager Firm Legal N")
                .withKeyValue(
                        "meta::relational::tests::semistructured::parseJson::testParseJsonInMapping_Connection_1__Boolean_1_",
                        "actual:   'First Name,Firm Name,Manager Firm Legal Name,Manager Manager")
                .withKeyValue(
                        "meta::relational::tests::semistructured::simple::relationFunctionMapping::testCombinedPrimitiveAndSemiStructuredPropertyAccessParallel_Connection_1__Boolean_1_",
                        "actual:   'First Name,Firm Legal Name")
                .withKeyValue(
                        "meta::relational::tests::semistructured::simple::relationFunctionMapping::testCombinedPrimitiveAndSemiStructuredPropertyAccess_Connection_1__Boolean_1_",
                        "actual:   'Out Col")
                .withKeyValue(
                        "meta::relational::tests::semistructured::simple::relationFunctionMapping::testFilterOnEnumPropertyWithEnumConst_Connection_1__Boolean_1_",
                        "No function matches the given name and argument types 'json_extract(JSON, BOOLEAN)'")
                .withKeyValue(
                        "meta::relational::tests::semistructured::simple::relationFunctionMapping::testFilterOnEnumPropertyWithStringConst_Connection_1__Boolean_1_",
                        "No function matches the given name and argument types 'json_extract(JSON, BOOLEAN)'")
                .withKeyValue(
                        "meta::relational::tests::semistructured::simple::relationFunctionMapping::testFilterOnEnumPropertyWithWithIfElseLogicEnum_Connection_1__Boolean_1_",
                        "Malformed JSON at byte 0 of input: unexpected character.  Input: \"Organization\"")
                .withKeyValue(
                        "meta::relational::tests::semistructured::simple::relationFunctionMapping::testFilterWithSemiStructuredProperty_Connection_1__Boolean_1_",
                        "No function matches the given name and argument types 'json_extract(JSON, BOOLEAN)'")
                .withKeyValue(
                        "meta::relational::tests::semistructured::simple::relationFunctionMapping::testGroupByOnEnumProperty_Connection_1__Boolean_1_",
                        "actual:   'Address,Names")
                .withKeyValue(
                        "meta::relational::tests::semistructured::simple::relationFunctionMapping::testGroupByWithSemiStructuredProperty_Connection_1__Boolean_1_",
                        "actual:   'Address,Names")
                .withKeyValue(
                        "meta::relational::tests::semistructured::simple::relationFunctionMapping::testIfElseLogicOnEnumProperties_Connection_1__Boolean_1_",
                        "actual:   'Enum Return")
                .withKeyValue(
                        "meta::relational::tests::semistructured::simple::relationFunctionMapping::testIsEmptyCheckOnSemiStructuredPrimitivePropertyAccess_Connection_1__Boolean_1_",
                        "No function matches the given name and argument types 'json_extract(JSON, BOOLEAN)'")
                .withKeyValue(
                        "meta::relational::tests::semistructured::simple::relationFunctionMapping::testIsEmptyCheckOnSemiStructuredPropertyAccessAfterAt_Connection_1__Boolean_1_",
                        "No function matches the given name and argument types 'json_extract(JSON, BOOLEAN)'")
                .withKeyValue(
                        "meta::relational::tests::semistructured::simple::relationFunctionMapping::testMultipleSemiStructuredPropertyAccessCombined_Connection_1__Boolean_1_",
                        "actual:   'Firm Legal Name And Address Name")
                .withKeyValue(
                        "meta::relational::tests::semistructured::simple::relationFunctionMapping::testMultipleSemiStructuredPropertyAccess_Connection_1__Boolean_1_",
                        "actual:   'Firm Legal Name,Firm Address Name")
                .withKeyValue(
                        "meta::relational::tests::semistructured::simple::relationFunctionMapping::testNestedSemiStructuredPropertyAccess_Connection_1__Boolean_1_",
                        "actual:   'Firm Address Name")
                .withKeyValue(
                        "meta::relational::tests::semistructured::simple::relationFunctionMapping::testSemiStructuredArrayElementAccessComplex_Connection_1__Boolean_1_",
                        "actual:   'First Name,Firm Address Line 0,Firm Address Line 1,Firm Addr")
                .withKeyValue(
                        "meta::relational::tests::semistructured::simple::relationFunctionMapping::testSemiStructuredArrayElementAccessPrimitive_Connection_1__Boolean_1_",
                        "Row 2 has too many columns")
                .withKeyValue(
                        "meta::relational::tests::semistructured::simple::relationFunctionMapping::testSemiStructuredDifferentDataTypePropertyAccess_Connection_1__Boolean_1_",
                        "actual:   'First Name,Firm Legal Name,Firm Employee Count,Firm Revenue,")
                .withKeyValue(
                        "meta::relational::tests::semistructured::simple::relationFunctionMapping::testSingleSemiStructuredPropertyAccess_Connection_1__Boolean_1_",
                        "actual:   'Firm Legal Name")
                .withKeyValue(
                        "meta::relational::tests::semistructured::simple::testCombinedComplexAndSemiStructuredPropertyAccessParallel_Connection_1__Boolean_1_",
                        "actual:   'Manager First Name,Firm Legal Name")
                .withKeyValue(
                        "meta::relational::tests::semistructured::simple::testCombinedComplexAndSemiStructuredPropertyAccess_Connection_1__Boolean_1_",
                        "actual:   'Out Col")
                .withKeyValue(
                        "meta::relational::tests::semistructured::simple::testCombinedPrimitiveAndSemiStructuredPropertyAccessParallel_Connection_1__Boolean_1_",
                        "actual:   'First Name,Firm Legal Name")
                .withKeyValue(
                        "meta::relational::tests::semistructured::simple::testCombinedPrimitiveAndSemiStructuredPropertyAccess_Connection_1__Boolean_1_",
                        "actual:   'Out Col")
                .withKeyValue(
                        "meta::relational::tests::semistructured::simple::testFilterOnEnumPropertyWithEnumConst_Connection_1__Boolean_1_",
                        "No function matches the given name and argument types 'json_extract(JSON, BOOLEAN)'")
                .withKeyValue(
                        "meta::relational::tests::semistructured::simple::testFilterOnEnumPropertyWithStringConst_Connection_1__Boolean_1_",
                        "No function matches the given name and argument types 'json_extract(JSON, BOOLEAN)'")
                .withKeyValue(
                        "meta::relational::tests::semistructured::simple::testFilterOnEnumPropertyWithWithIfElseLogicEnum_Connection_1__Boolean_1_",
                        "Malformed JSON at byte 0 of input: unexpected character.  Input: \"Organization\"")
                .withKeyValue(
                        "meta::relational::tests::semistructured::simple::testFilterWithSemiStructuredPropertyAccessAtNestedProperty_Connection_1__Boolean_1_",
                        "No function matches the given name and argument types 'json_extract(JSON, BOOLEAN)'")
                .withKeyValue(
                        "meta::relational::tests::semistructured::simple::testFilterWithSemiStructuredProperty_Connection_1__Boolean_1_",
                        "No function matches the given name and argument types 'json_extract(JSON, BOOLEAN)'")
                .withKeyValue(
                        "meta::relational::tests::semistructured::simple::testGroupByOnEnumProperty_Connection_1__Boolean_1_",
                        "actual:   'Address,Names")
                .withKeyValue(
                        "meta::relational::tests::semistructured::simple::testGroupByWithSemiStructuredProperty_Connection_1__Boolean_1_",
                        "actual:   'Address,Names")
                .withKeyValue(
                        "meta::relational::tests::semistructured::simple::testIfElseLogicOnEnumProperties_Connection_1__Boolean_1_",
                        "actual:   'Enum Return")
                .withKeyValue(
                        "meta::relational::tests::semistructured::simple::testIsEmptyCheckOnSemiStructuredPrimitivePropertyAccess_Connection_1__Boolean_1_",
                        "No function matches the given name and argument types 'json_extract(JSON, BOOLEAN)'")
                .withKeyValue(
                        "meta::relational::tests::semistructured::simple::testIsEmptyCheckOnSemiStructuredPropertyAccessAfterAt_Connection_1__Boolean_1_",
                        "No function matches the given name and argument types 'json_extract(JSON, BOOLEAN)'")
                .withKeyValue(
                        "meta::relational::tests::semistructured::simple::testMultipleSemiStructuredPropertyAccessCombined_Connection_1__Boolean_1_",
                        "actual:   'Firm Legal Name And Address Name")
                .withKeyValue(
                        "meta::relational::tests::semistructured::simple::testMultipleSemiStructuredPropertyAccess_Connection_1__Boolean_1_",
                        "actual:   'Firm Legal Name,Firm Address Name")
                .withKeyValue(
                        "meta::relational::tests::semistructured::simple::testNestedSemiStructuredPropertyAccess_Connection_1__Boolean_1_",
                        "actual:   'Firm Address Name")
                .withKeyValue(
                        "meta::relational::tests::semistructured::simple::testSemiStructuredArrayElementAccessComplex_Connection_1__Boolean_1_",
                        "actual:   'First Name,Firm Address Line 0,Firm Address Line 1,Firm Addr")
                .withKeyValue(
                        "meta::relational::tests::semistructured::simple::testSemiStructuredArrayElementAccessPrimitive_Connection_1__Boolean_1_",
                        "Row 2 has too many columns")
                .withKeyValue(
                        "meta::relational::tests::semistructured::simple::testSemiStructuredDifferentDataTypePropertyAccess_Connection_1__Boolean_1_",
                        "actual:   'First Name,Firm Legal Name,Firm Employee Count,Firm Revenue,")
                .withKeyValue(
                        "meta::relational::tests::semistructured::simple::testSemiStructuredPropertyAccessAtNestedPropertyWithProjectFunctions_Connection_1__Boolean_1_",
                        "actual:   'First Name,Firm Name,Manager Firm Name,Manager Manager Firm ")
                .withKeyValue(
                        "meta::relational::tests::semistructured::simple::testSemiStructuredPropertyAccessAtNestedProperty_Connection_1__Boolean_1_",
                        "actual:   'First Name,Firm Name,Manager Firm Name,Manager Manager Firm ")
                .withKeyValue(
                        "meta::relational::tests::semistructured::simple::testSingleSemiStructuredPropertyAccess_Connection_1__Boolean_1_",
                        "actual:   'Firm Legal Name")
                .withKeyValue(
                        "meta::relational::tests::semistructured::typeFunctions::relationFunctionMapping::testSemiStructuredTypeNameFunctionUsageAfterArrayElementAccess_Connection_1__Boolean_1_",
                        "actual:   'Order Id,Product 0 Type,Product 1 Type")
                .withKeyValue(
                        "meta::relational::tests::semistructured::typeFunctions::relationFunctionMapping::testSemiStructuredTypeNameFunctionUsageAfterFlattenColSpec_Connection_1__Boolean_1_",
                        "Match failure: SemiStructur")
                .withKeyValue(
                        "meta::relational::tests::semistructured::typeFunctions::relationFunctionMapping::testSemiStructuredTypeNameFunctionUsageAfterFlattenFunction_Connection_1__Boolean_1_",
                        "Match failure: SemiStructur")
                .withKeyValue(
                        "meta::relational::tests::semistructured::typeFunctions::relationFunctionMapping::testSemiStructuredTypeNameFunctionUsageInFilter_Connection_1__Boolean_1_",
                        "No function matches the given name and argument types 'json_extract(JSON, BOOLEAN)'")
                .withKeyValue(
                        "meta::relational::tests::semistructured::typeFunctions::relationFunctionMapping::testSemiStructuredTypeNameFunctionUsageInProjectWithColSpecs_Connection_1__Boolean_1_",
                        "actual:   'Customer Address Type,Payment Type")
                .withKeyValue(
                        "meta::relational::tests::semistructured::typeFunctions::relationFunctionMapping::testSemiStructuredTypeNameFunctionUsageInProjectWithFunctions_Connection_1__Boolean_1_",
                        "actual:   'Customer Address Type,Payment Type")
                .withKeyValue(
                        "meta::relational::tests::semistructured::typeFunctions::testSemiStructuredTypeNameFunctionUsageAfterArrayElementAccess_Connection_1__Boolean_1_",
                        "actual:   'Order Id,Product 0 Type,Product 1 Type")
                .withKeyValue(
                        "meta::relational::tests::semistructured::typeFunctions::testSemiStructuredTypeNameFunctionUsageAfterFlattenColSpec_Connection_1__Boolean_1_",
                        "Match failure: SemiStructur")
                .withKeyValue(
                        "meta::relational::tests::semistructured::typeFunctions::testSemiStructuredTypeNameFunctionUsageAfterFlattenFunction_Connection_1__Boolean_1_",
                        "Match failure: SemiStructur")
                .withKeyValue(
                        "meta::relational::tests::semistructured::typeFunctions::testSemiStructuredTypeNameFunctionUsageInFilter_Connection_1__Boolean_1_",
                        "No function matches the given name and argument types 'json_extract(JSON, BOOLEAN)'")
                .withKeyValue(
                        "meta::relational::tests::semistructured::typeFunctions::testSemiStructuredTypeNameFunctionUsageInProjectWithColSpecs_Connection_1__Boolean_1_",
                        "actual:   'Customer Address Type,Payment Type")
                .withKeyValue(
                        "meta::relational::tests::semistructured::typeFunctions::testSemiStructuredTypeNameFunctionUsageInProjectWithFunctions_Connection_1__Boolean_1_",
                        "actual:   'Customer Address Type,Payment Type")
                .withKeyValue(
                        "meta::relational::tests::semistructured::union::testSemiStructuredUnionMappingWithBindingAndFilter_Connection_1__Boolean_1_",
                        "No function matches the given name and argument types 'json_extract(JSON, BOOLEAN)'")
                .withKeyValue(
                        "meta::relational::tests::semistructured::union::testSemiStructuredUnionMappingWithBinding_Connection_1__Boolean_1_",
                        "actual:   'Firm/FirmName")
                .withKeyValue(
                        "meta::relational::tests::semistructured::unionFlatten::testSemiStructuredUnionFlattenWithAssociation_Connection_1__Boolean_1_",
                        "Match failure: SemiStructur");

        Map<CoreInstance, String> failures = pathToReason.collect(
                (k, v) -> Tuples.pair(executionSupport.getProcessorSupport().package_getByUserPath(k), v));

        PureTestBuilder.F2<CoreInstance, MutableList<Object>, Object> executor = (test, params) ->
        {
            try
            {
                return PureTestBuilderCompiled.executeFn(test, null, Maps.mutable.empty(), executionSupport, params);
            }
            // Throwable rather than Exception: a wrong-result test fails with an AssertionError.
            // Anything not named in pathToReason is still rethrown untouched.
            catch (Throwable e)
            {
                String reason = failures.get(test);
                if (reason != null)
                {
                    if (e.getMessage() == null || !e.getMessage().contains(reason))
                    {
                        throw new AssertionError("Expect failure to contains: " + reason, e);
                    }
                    return true;
                }
                throw e;
            }
        };

        return wrapSuite(
                () -> true,
                () -> PureTestBuilder.buildSuite(
                        TestCollection.collectTests(testPackage, executionSupport.getProcessorSupport(),
                                fn -> PureTestBuilderCompiled.generatePureTestCollection(fn, executionSupport),
                                ci -> PureTestBuilder.satisfiesConditions(ci, executionSupport.getProcessorSupport())
                        ),
                        executor,
                        executionSupport
                ),
                () -> false,
                Lists.mutable.with((TestServerResource) TestConnectionIntegrationLoader.extensions().select(c -> c.getDatabaseType() == DatabaseType.DuckDB).getFirst())
        );
    }
}
