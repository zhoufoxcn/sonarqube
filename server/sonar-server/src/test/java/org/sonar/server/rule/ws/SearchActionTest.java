/*
 * SonarQube
 * Copyright (C) 2009-2017 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package org.sonar.server.rule.ws;

import java.io.IOException;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.assertj.core.api.iterable.Extractor;
import org.junit.Before;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.sonar.api.config.MapSettings;
import org.sonar.api.resources.Languages;
import org.sonar.api.rule.RuleKey;
import org.sonar.api.rules.RuleType;
import org.sonar.api.server.ws.WebService;
import org.sonar.api.utils.System2;
import org.sonar.api.utils.internal.AlwaysIncreasingSystem2;
import org.sonar.core.util.stream.MoreCollectors;
import org.sonar.db.DbTester;
import org.sonar.db.organization.OrganizationDto;
import org.sonar.db.rule.RuleDefinitionDto;
import org.sonar.db.rule.RuleMetadataDto;
import org.sonar.server.es.EsTester;
import org.sonar.server.language.LanguageTesting;
import org.sonar.server.organization.DefaultOrganizationProvider;
import org.sonar.server.organization.TestDefaultOrganizationProvider;
import org.sonar.server.rule.index.RuleIndex;
import org.sonar.server.rule.index.RuleIndexDefinition;
import org.sonar.server.rule.index.RuleIndexer;
import org.sonar.server.tester.UserSessionRule;
import org.sonar.server.text.MacroInterpreter;
import org.sonar.server.ws.TestRequest;
import org.sonar.server.ws.WsActionTester;
import org.sonarqube.ws.Rules;
import org.sonarqube.ws.Rules.Rule;
import org.sonarqube.ws.Rules.SearchResponse;

import static java.util.Arrays.stream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.guava.api.Assertions.entry;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.sonar.db.rule.RuleTesting.setSystemTags;
import static org.sonar.db.rule.RuleTesting.setTags;
import static org.sonarqube.ws.client.rule.RulesWsParameters.PARAM_RULE_KEY;

public class SearchActionTest {

  @org.junit.Rule
  public UserSessionRule userSessionRule = UserSessionRule.standalone();
  @org.junit.Rule
  public ExpectedException thrown = ExpectedException.none();

  private System2 system2 = new AlwaysIncreasingSystem2();
  @org.junit.Rule
  public DbTester db = DbTester.create(system2);
  @org.junit.Rule
  public EsTester es = new EsTester(new RuleIndexDefinition(new MapSettings()));

  private DefaultOrganizationProvider defaultOrganizationProvider = TestDefaultOrganizationProvider.from(db);
  private RuleIndex ruleIndex = new RuleIndex(es.client());
  private RuleIndexer ruleIndexer = new RuleIndexer(es.client(), db.getDbClient());
  private Languages languages = LanguageTesting.newLanguages("java", "js");
  private ActiveRuleCompleter activeRuleCompleter = new ActiveRuleCompleter(db.getDbClient(), languages);
  private RuleWsSupport wsSupport = new RuleWsSupport(db.getDbClient(), userSessionRule, defaultOrganizationProvider);
  private RuleQueryFactory ruleQueryFactory = new RuleQueryFactory(db.getDbClient(), wsSupport);
  private MacroInterpreter macroInterpreter = mock(MacroInterpreter.class);
  private RuleMapper ruleMapper = new RuleMapper(languages, macroInterpreter);
  private SearchAction underTest = new SearchAction(ruleIndex, activeRuleCompleter, ruleQueryFactory, db.getDbClient(), ruleMapper);
  private WsActionTester ws = new WsActionTester(underTest);

  @Before
  public void before() {
    doReturn("interpreted").when(macroInterpreter).interpret(anyString());
  }

  @Test
  public void test_definition() {
    assertThat(ws.getDef().isPost()).isFalse();
    assertThat(ws.getDef().since()).isEqualTo("4.4");
    assertThat(ws.getDef().isInternal()).isFalse();
    assertThat(ws.getDef().params()).hasSize(22);
  }

  @Test
  public void return_empty_result() {
    Rules.SearchResponse response = ws.newRequest()
      .setParam(WebService.Param.FIELDS, "actives")
      .executeProtobuf(Rules.SearchResponse.class);

    assertThat(response.getTotal()).isEqualTo(0L);
    assertThat(response.getP()).isEqualTo(1);
    assertThat(response.getRulesCount()).isEqualTo(0);
  }

  @Test
  public void return_all_rules() {
    RuleDefinitionDto rule1 = createJavaRule();
    RuleDefinitionDto rule2 = createJavaRule();
    index();

    verify(r -> {
    }, rule1, rule2);
  }

  @Test
  public void filter_by_rule_key() {
    RuleDefinitionDto rule1 = createJavaRule();
    RuleDefinitionDto rule2 = createJavaRule();
    index();

    verify(r -> r.setParam(PARAM_RULE_KEY, rule1.getKey().toString()), rule1);
    verifyNoResults(r -> r.setParam(PARAM_RULE_KEY, "missing"));
  }

  @Test
  public void return_all_rule_fields_by_default() {
    RuleDefinitionDto rule = createJavaRule();
    index();

    Rules.SearchResponse response = ws.newRequest().executeProtobuf(Rules.SearchResponse.class);
    Rules.Rule result = response.getRules(0);
    assertThat(result.getCreatedAt()).isNotEmpty();
    assertThat(result.getEffortToFixDescription()).isNotEmpty();
    assertThat(result.getHtmlDesc()).isNotEmpty();
    assertThat(result.hasIsTemplate()).isTrue();
    assertThat(result.getLang()).isEqualTo(rule.getLanguage());
    assertThat(result.getLangName()).isEqualTo(languages.get(rule.getLanguage()).getName());
    assertThat(result.getName()).isNotEmpty();
    assertThat(result.getRepo()).isNotEmpty();
    assertThat(result.getSeverity()).isNotEmpty();
    assertThat(result.getType().name()).isEqualTo(RuleType.valueOf(rule.getType()).name());
  }

  @Test
  public void return_subset_of_fields() {
    RuleDefinitionDto rule = createJavaRule();
    index();

    Rules.SearchResponse response = ws.newRequest()
      .setParam(WebService.Param.FIELDS, "createdAt,langName")
      .executeProtobuf(Rules.SearchResponse.class);
    Rules.Rule result = response.getRules(0);

    // mandatory fields
    assertThat(result.getKey()).isEqualTo(rule.getKey().toString());
    assertThat(result.getType().getNumber()).isEqualTo(rule.getType());

    // selected fields
    assertThat(result.getCreatedAt()).isNotEmpty();
    assertThat(result.getLangName()).isNotEmpty();

    // not returned fields
    assertThat(result.hasEffortToFixDescription()).isFalse();
    assertThat(result.hasHtmlDesc()).isFalse();
    assertThat(result.hasIsTemplate()).isFalse();
    assertThat(result.hasLang()).isFalse();
    assertThat(result.hasName()).isFalse();
    assertThat(result.hasSeverity()).isFalse();
    assertThat(result.hasRepo()).isFalse();
  }

  @Test
  public void should_filter_on_organization_specific_tags() throws IOException {
    OrganizationDto organization = db.organizations().insert();
    RuleDefinitionDto rule1 = createJavaRule();
    RuleMetadataDto metadata1 = insertMetadata(organization, rule1, setTags("tag1", "tag2"));
    RuleDefinitionDto rule2 = createJavaRule();
    RuleMetadataDto metadata2 = insertMetadata(organization, rule2);
    index();

    Consumer<TestRequest> request = r -> r
      .setParam("f", "repo,name")
      .setParam("tags", metadata1.getTags().stream().collect(Collectors.joining(",")))
      .setParam("organization", organization.getKey());
    verify(request, rule1);
  }

  @Test
  public void should_list_tags_in_tags_facet() throws IOException {
    OrganizationDto organization = db.organizations().insert();
    RuleDefinitionDto rule = db.rules().insert(setSystemTags("tag1", "tag3", "tag5", "tag7", "tag9", "x"));
    RuleMetadataDto metadata = insertMetadata(organization, rule, setTags("tag2", "tag4", "tag6", "tag8", "tagA"));
    index();

    SearchResponse result = ws.newRequest()
      .setParam("facets", "tags")
      .setParam("organization", organization.getKey())
      .executeProtobuf(SearchResponse.class);
    assertThat(result.getFacets().getFacets(0).getValuesList()).extracting(v -> entry(v.getVal(), v.getCount()))
      .containsExactly(entry("tag1", 1L), entry("tag2", 1L), entry("tag3", 1L), entry("tag4", 1L), entry("tag5", 1L), entry("tag6", 1L), entry("tag7", 1L), entry("tag8", 1L),
        entry("tag9", 1L), entry("tagA", 1L));
  }

  @Test
  public void should_include_selected_matching_tag_in_facet() throws IOException {
    RuleDefinitionDto rule = db.rules().insert(setSystemTags("tag1", "tag2", "tag3", "tag4", "tag5", "tag6", "tag7", "tag8", "tag9", "tagA", "x"));
    index();

    SearchResponse result = ws.newRequest()
      .setParam("facets", "tags")
      .setParam("tags", "x")
      .executeProtobuf(SearchResponse.class);
    assertThat(result.getFacets().getFacets(0).getValuesList()).extracting(v -> entry(v.getVal(), v.getCount())).contains(entry("x", 1L));
  }

  @Test
  public void should_included_selected_non_matching_tag_in_facet() throws IOException {
    RuleDefinitionDto rule = db.rules().insert(setSystemTags("tag1", "tag2", "tag3", "tag4", "tag5", "tag6", "tag7", "tag8", "tag9", "tagA"));
    index();

    SearchResponse result = ws.newRequest()
      .setParam("facets", "tags")
      .setParam("tags", "x")
      .executeProtobuf(SearchResponse.class);
    assertThat(result.getFacets().getFacets(0).getValuesList()).extracting(v -> entry(v.getVal(), v.getCount())).contains(entry("x", 0L));
  }

  @Test
  public void should_return_organization_specific_tags() throws IOException {
    OrganizationDto organization = db.organizations().insert();
    RuleDefinitionDto rule = createJavaRule();
    RuleMetadataDto metadata = insertMetadata(organization, rule, setTags("tag1", "tag2"));
    index();

    SearchResponse result = ws.newRequest()
      .setParam("f", "tags")
      .setParam("organization", organization.getKey())
      .executeProtobuf(SearchResponse.class);
    assertThat(result.getRulesList()).extracting(Rule::getKey).containsExactly(rule.getKey().toString());
    assertThat(result.getRulesList())
      .extracting(Rule::getTags).flatExtracting(Rules.Tags::getTagsList)
      .containsExactly(metadata.getTags().toArray(new String[0]));
  }

  @Test
  public void should_return_specified_fields() throws Exception {
    RuleDefinitionDto rule = createJavaRule();
    index();

    checkField(rule, "repo", Rule::getRepo, rule.getRepositoryKey());
    checkField(rule, "name", Rule::getName, rule.getName());
    checkField(rule, "severity", Rule::getSeverity, rule.getSeverityString());
    checkField(rule, "status", r -> r.getStatus().toString(), rule.getStatus().toString());
    checkField(rule, "internalKey", Rule::getInternalKey, rule.getConfigKey());
    checkField(rule, "isTemplate", Rule::getIsTemplate, rule.isTemplate());
    checkField(rule, "sysTags",
      r -> r.getSysTags().getSysTagsList().stream().collect(Collectors.joining(",")),
      rule.getSystemTags().stream().collect(Collectors.joining(",")));
    checkField(rule, "lang", Rule::getLang, rule.getLanguage());
    checkField(rule, "langName", Rule::getLangName, languages.get(rule.getLanguage()).getName());
    checkField(rule, "gapDescription", Rule::getGapDescription, rule.getGapDescription());
    // to be continued...
  }

  @SafeVarargs
  private final <T> void checkField(RuleDefinitionDto rule, String fieldName, Extractor<Rule, T> responseExtractor, T... expected) throws IOException {
    SearchResponse result = ws.newRequest()
      .setParam("f", fieldName)
      .executeProtobuf(SearchResponse.class);
    assertThat(result.getRulesList()).extracting(Rule::getKey).containsExactly(rule.getKey().toString());
    assertThat(result.getRulesList()).extracting(responseExtractor).containsExactly(expected);
  }

  @SafeVarargs
  private final RuleMetadataDto insertMetadata(OrganizationDto organization, RuleDefinitionDto rule, Consumer<RuleMetadataDto>... populaters) {
    RuleMetadataDto metadata = db.rules().insertOrUpdateMetadata(rule, organization, populaters);
    ruleIndexer.indexRuleExtension(organization, rule.getKey());
    return metadata;
  }

  private void verifyNoResults(Consumer<TestRequest> requestPopulator) {
    verify(requestPopulator);
  }

  private void verify(Consumer<TestRequest> requestPopulator, RuleDefinitionDto... expectedRules) {
    TestRequest request = ws.newRequest();
    requestPopulator.accept(request);
    Rules.SearchResponse response = request
      .executeProtobuf(Rules.SearchResponse.class);

    assertThat(response.getP()).isEqualTo(1);
    assertThat(response.getTotal()).isEqualTo(expectedRules.length);
    assertThat(response.getRulesCount()).isEqualTo(expectedRules.length);
    RuleKey[] expectedRuleKeys = stream(expectedRules).map(RuleDefinitionDto::getKey).collect(MoreCollectors.toList()).toArray(new RuleKey[0]);
    assertThat(response.getRulesList())
      .extracting(r -> RuleKey.parse(r.getKey()))
      .containsExactlyInAnyOrder(expectedRuleKeys);
  }

  private void index() {
    ruleIndexer.indexOnStartup(ruleIndexer.getIndexTypes());
  }

  private RuleDefinitionDto createJavaRule() {
    return db.rules().insert(r -> r.setLanguage("java"));
  }
}
