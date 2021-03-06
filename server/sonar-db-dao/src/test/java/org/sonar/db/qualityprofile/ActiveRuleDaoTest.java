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
package org.sonar.db.qualityprofile;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.sonar.api.rule.RuleStatus;
import org.sonar.api.rule.Severity;
import org.sonar.api.server.rule.RuleParamType;
import org.sonar.api.utils.System2;
import org.sonar.api.utils.internal.TestSystem2;
import org.sonar.db.DbSession;
import org.sonar.db.DbTester;
import org.sonar.db.organization.OrganizationDto;
import org.sonar.db.organization.OrganizationTesting;
import org.sonar.db.rule.RuleDefinitionDto;
import org.sonar.db.rule.RuleParamDto;
import org.sonar.db.rule.RuleTesting;

import static com.google.common.collect.Lists.newArrayList;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.data.MapEntry.entry;
import static org.sonar.api.rule.Severity.BLOCKER;
import static org.sonar.api.rule.Severity.MAJOR;
import static org.sonar.db.qualityprofile.ActiveRuleDto.INHERITED;
import static org.sonar.db.qualityprofile.ActiveRuleDto.OVERRIDES;
import static org.sonar.db.qualityprofile.ActiveRuleDto.createFor;

public class ActiveRuleDaoTest {

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  private static final long NOW = 10_000_000L;

  private OrganizationDto organization;
  private QProfileDto profile1;
  private QProfileDto profile2;
  private RuleDefinitionDto rule1;
  private RuleDefinitionDto rule2;
  private RuleDefinitionDto rule3;
  private RuleDefinitionDto removedRule;
  private RuleParamDto rule1Param1;
  private RuleParamDto rule1Param2;
  private RuleParamDto rule2Param1;
  private System2 system = new TestSystem2().setNow(NOW);

  @Rule
  public DbTester db = DbTester.create(system);

  private DbSession dbSession = db.getSession();

  private ActiveRuleDao underTest = db.getDbClient().activeRuleDao();

  @Before
  public void setUp() {
    organization = db.organizations().insert();
    profile1 = db.qualityProfiles().insert(organization);
    profile2 = db.qualityProfiles().insert(organization);
    rule1 = db.rules().insert();
    rule2 = db.rules().insert();
    rule3 = db.rules().insert();
    removedRule = db.rules().insert(r -> r.setStatus(RuleStatus.REMOVED));

    rule1Param1 = new RuleParamDto()
      .setName("param1")
      .setDefaultValue("value1")
      .setType(RuleParamType.STRING.toString());
    rule1Param1 = db.rules().insertRuleParam(rule1);
    rule1Param2 = db.rules().insertRuleParam(rule1);
    rule2Param1 = db.rules().insertRuleParam(rule2);
  }

  @Test
  public void selectByKey() {
    ActiveRuleDto activeRule = createFor(profile1, rule1).setSeverity(BLOCKER);
    underTest.insert(dbSession, activeRule);

    assertThat(underTest.selectByKey(dbSession, activeRule.getKey())).isPresent();
    assertThat(underTest.selectByKey(dbSession, ActiveRuleKey.of(profile2, rule2.getKey()))).isEmpty();
  }

  @Test
  public void select_by_rule() {
    ActiveRuleDto activeRule1 = createFor(profile1, rule1).setSeverity(BLOCKER);
    ActiveRuleDto activeRule2 = createFor(profile2, rule1).setSeverity(BLOCKER);
    underTest.insert(dbSession, activeRule1);
    underTest.insert(dbSession, activeRule2);
    dbSession.commit();

    assertThat(underTest.selectByRuleId(dbSession, organization, rule1.getId())).extracting("key").containsOnly(activeRule1.getKey(), activeRule2.getKey());
    assertThat(underTest.selectByRuleId(dbSession, organization, rule3.getId())).isEmpty();
  }

  @Test
  public void select_by_rule_ids() {
    ActiveRuleDto activeRule1 = createFor(profile1, rule1).setSeverity(BLOCKER);
    ActiveRuleDto activeRule2 = createFor(profile1, rule2).setSeverity(BLOCKER);
    ActiveRuleDto activeRule3 = createFor(profile2, rule1).setSeverity(BLOCKER);
    underTest.insert(dbSession, activeRule1);
    underTest.insert(dbSession, activeRule2);
    underTest.insert(dbSession, activeRule3);
    dbSession.commit();

    assertThat(underTest.selectByRuleIds(dbSession, organization, Collections.singletonList(rule1.getId())))
      .extracting("key").containsOnly(activeRule1.getKey(), activeRule3.getKey());
    assertThat(underTest.selectByRuleIds(dbSession, organization, newArrayList(rule1.getId(), rule2.getId())))
      .extracting("key").containsOnly(activeRule1.getKey(), activeRule2.getKey(), activeRule3.getKey());
  }

  @Test
  public void selectByProfile() {
    ActiveRuleDto activeRule1 = createFor(profile1, rule1).setSeverity(BLOCKER);
    ActiveRuleDto activeRule2 = createFor(profile1, rule2).setSeverity(BLOCKER);
    underTest.insert(dbSession, activeRule1);
    underTest.insert(dbSession, activeRule2);

    List<OrgActiveRuleDto> result = underTest.selectByProfile(dbSession, profile1);
    assertThat(result)
      .hasSize(2)
      .extracting(OrgActiveRuleDto::getOrganizationUuid, OrgActiveRuleDto::getProfileUuid, OrgActiveRuleDto::getProfileId)
      .containsOnly(tuple(organization.getUuid(), profile1.getKee(), profile1.getId()));

    assertThat(underTest.selectByProfile(dbSession, profile2)).isEmpty();
  }

  @Test
  public void selectByProfileUuid_ignores_removed_rules() {
    ActiveRuleDto activeRule = createFor(profile1, removedRule).setSeverity(BLOCKER);
    underTest.insert(dbSession, activeRule);

    assertThat(underTest.selectByProfile(dbSession, profile1)).isEmpty();
  }

  @Test
  public void selectByRuleProfileUuid() {
    ActiveRuleDto activeRule1 = createFor(profile1, rule1).setSeverity(BLOCKER);
    ActiveRuleDto activeRule2 = createFor(profile1, rule2).setSeverity(MAJOR);
    underTest.insert(dbSession, activeRule1);
    underTest.insert(dbSession, activeRule2);

    List<ActiveRuleDto> result = underTest.selectByRuleProfile(dbSession, RulesProfileDto.from(profile1));
    assertThat(result)
      .hasSize(2)
      .extracting(ActiveRuleDto::getProfileId, ActiveRuleDto::getRuleKey, ActiveRuleDto::getSeverityString)
      .containsOnly(tuple(profile1.getId(), rule1.getKey(), BLOCKER), tuple(profile1.getId(), rule2.getKey(), MAJOR));

    assertThat(underTest.selectByProfile(dbSession, profile2)).isEmpty();
  }

  @Test
  public void insert() {
    ActiveRuleDto activeRule = createFor(profile1, rule1)
      .setSeverity(BLOCKER)
      .setInheritance(INHERITED)
      .setCreatedAt(1000L)
      .setUpdatedAt(2000L);
    underTest.insert(dbSession, activeRule);
    dbSession.commit();

    ActiveRuleDto result = underTest.selectByKey(dbSession, activeRule.getKey()).get();
    assertThat(result.getId()).isEqualTo(activeRule.getId());
    assertThat(result.getKey()).isEqualTo(ActiveRuleKey.of(profile1, rule1.getKey()));
    assertThat(result.getRuleId()).isEqualTo(rule1.getId());
    assertThat(result.getProfileId()).isEqualTo(profile1.getId());
    assertThat(result.getSeverityString()).isEqualTo(BLOCKER);
    assertThat(result.getInheritance()).isEqualTo(INHERITED);
    assertThat(result.getCreatedAt()).isEqualTo(1000L);
    assertThat(result.getUpdatedAt()).isEqualTo(2000L);
  }

  @Test
  public void fail_to_insert_when_profile_id_is_null() {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("Quality profile is not persisted (missing id)");

    underTest.insert(dbSession, createFor(profile1, rule1).setProfileId(null));
  }

  @Test
  public void fail_to_insert_when_rule_id_is_null() {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("Rule is not persisted");

    underTest.insert(dbSession, createFor(profile1, rule1).setRuleId(null));
  }

  @Test
  public void fail_to_insert_when_id_is_not_null() {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("ActiveRule is already persisted");

    underTest.insert(dbSession, createFor(profile1, rule1).setId(100));
  }

  @Test
  public void update() {
    ActiveRuleDto activeRule = createFor(profile1, rule1)
      .setSeverity(BLOCKER)
      .setInheritance(INHERITED)
      .setCreatedAt(1000L)
      .setUpdatedAt(2000L);
    underTest.insert(dbSession, activeRule);
    dbSession.commit();

    ActiveRuleDto activeRuleUpdated = activeRule
      .setSeverity(MAJOR)
      .setInheritance(OVERRIDES)
      // created at should not be updated
      .setCreatedAt(3000L)
      .setUpdatedAt(4000L);
    underTest.update(dbSession, activeRuleUpdated);
    dbSession.commit();

    ActiveRuleDto result = underTest.selectByKey(dbSession, ActiveRuleKey.of(profile1, rule1.getKey())).get();
    assertThat(result.getId()).isEqualTo(activeRule.getId());
    assertThat(result.getKey()).isEqualTo(ActiveRuleKey.of(profile1, rule1.getKey()));
    assertThat(result.getRuleId()).isEqualTo(rule1.getId());
    assertThat(result.getProfileId()).isEqualTo(profile1.getId());
    assertThat(result.getSeverityString()).isEqualTo(MAJOR);
    assertThat(result.getInheritance()).isEqualTo(OVERRIDES);
    assertThat(result.getCreatedAt()).isEqualTo(1000L);
    assertThat(result.getUpdatedAt()).isEqualTo(4000L);
  }

  @Test
  public void fail_to_update_when_profile_id_is_null() {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("Quality profile is not persisted (missing id)");

    underTest.update(dbSession, createFor(profile1, rule1).setId(100).setProfileId(null));
  }

  @Test
  public void fail_to_update_when_rule_id_is_null() {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("Rule is not persisted");

    underTest.update(dbSession, createFor(profile1, rule1).setId(100).setRuleId(null));
  }

  @Test
  public void fail_to_update_when_id_is_null() {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("ActiveRule is not persisted");

    underTest.update(dbSession, createFor(profile1, rule1).setId(null));
  }

  @Test
  public void delete() {
    ActiveRuleDto activeRule = createFor(profile1, rule1)
      .setSeverity(BLOCKER)
      .setInheritance(INHERITED)
      .setCreatedAt(1000L)
      .setUpdatedAt(2000L);
    underTest.insert(dbSession, activeRule);

    underTest.delete(dbSession, activeRule.getKey());

    assertThat(underTest.selectByKey(dbSession, ActiveRuleKey.of(profile1, rule1.getKey()))).isEmpty();
  }

  @Test
  public void delete_does_not_fail_when_active_rule_does_not_exist() {
    underTest.delete(dbSession, ActiveRuleKey.of(profile1, rule1.getKey()));
  }

  @Test
  public void deleteByRuleProfileUuids_deletes_rows_from_table() {
    underTest.insert(dbSession, newRow(profile1, rule1));
    underTest.insert(dbSession, newRow(profile1, rule2));
    underTest.insert(dbSession, newRow(profile2, rule1));

    underTest.deleteByRuleProfileUuids(dbSession, asList(profile1.getRulesProfileUuid()));

    assertThat(db.countRowsOfTable(dbSession, "active_rules")).isEqualTo(1);
    assertThat(underTest.selectByKey(dbSession, ActiveRuleKey.of(profile2, rule1.getKey()))).isPresent();
  }

  @Test
  public void deleteByRuleProfileUuids_does_not_fail_when_rules_profile_with_specified_key_does_not_exist() {
    underTest.insert(dbSession, newRow(profile1, rule1));

    underTest.deleteByRuleProfileUuids(dbSession, asList("does_not_exist"));

    assertThat(db.countRowsOfTable(dbSession, "active_rules")).isEqualTo(1);
  }

  @Test
  public void deleteByIds() {
    ActiveRuleDto ar1 = underTest.insert(dbSession, newRow(profile1, rule1));
    ActiveRuleDto ar2 = underTest.insert(dbSession, newRow(profile1, rule2));
    ActiveRuleDto ar3 = underTest.insert(dbSession, newRow(profile2, rule1));

    underTest.deleteByIds(dbSession, asList(ar1.getId(), ar3.getId()));

    assertThat(db.countRowsOfTable(dbSession, "active_rules")).isEqualTo(1);
    assertThat(underTest.selectByProfile(dbSession, profile1))
      .extracting(ActiveRuleDto::getId)
      .containsExactly(ar2.getId());
  }

  @Test
  public void deleteByIds_does_nothing_if_empty_list_of_ids() {
    underTest.insert(dbSession, newRow(profile1, rule1));

    underTest.deleteByIds(dbSession, emptyList());

    assertThat(db.countRowsOfTable(dbSession, "active_rules")).isEqualTo(1);
  }

  private static ActiveRuleDto newRow(QProfileDto profile, RuleDefinitionDto rule) {
    return createFor(profile, rule).setSeverity(BLOCKER);
  }

  @Test
  public void select_params_by_active_rule_id() {
    ActiveRuleDto activeRule = createFor(profile1, rule1).setSeverity(BLOCKER);
    underTest.insert(dbSession, activeRule);
    ActiveRuleParamDto activeRuleParam1 = ActiveRuleParamDto.createFor(rule1Param1);
    underTest.insertParam(dbSession, activeRule, activeRuleParam1);
    ActiveRuleParamDto activeRuleParam2 = ActiveRuleParamDto.createFor(rule1Param2);
    underTest.insertParam(dbSession, activeRule, activeRuleParam2);
    dbSession.commit();

    assertThat(underTest.selectParamsByActiveRuleId(dbSession, activeRule.getId())).hasSize(2);
  }

  @Test
  public void select_params_by_active_rule_ids() {
    ActiveRuleDto activeRule1 = createFor(profile1, rule1).setSeverity(BLOCKER);
    underTest.insert(dbSession, activeRule1);
    underTest.insertParam(dbSession, activeRule1, ActiveRuleParamDto.createFor(rule1Param1));
    underTest.insertParam(dbSession, activeRule1, ActiveRuleParamDto.createFor(rule1Param2));

    ActiveRuleDto activeRule2 = createFor(profile1, rule2).setSeverity(BLOCKER);
    underTest.insert(dbSession, activeRule2);
    underTest.insertParam(dbSession, activeRule2, ActiveRuleParamDto.createFor(rule2Param1));
    dbSession.commit();

    assertThat(underTest.selectParamsByActiveRuleIds(dbSession, asList(activeRule1.getId(), activeRule2.getId()))).hasSize(3);
  }

  @Test
  public void insertParam() {
    ActiveRuleDto activeRule = createFor(profile1, rule1).setSeverity(Severity.CRITICAL);
    underTest.insert(dbSession, activeRule);

    ActiveRuleParamDto activeRuleParam = ActiveRuleParamDto.createFor(rule1Param1).setValue("foo");
    underTest.insertParam(dbSession, activeRule, activeRuleParam);

    List<ActiveRuleParamDto> reloaded = underTest.selectParamsByActiveRuleId(dbSession, activeRule.getId());
    assertThat(reloaded).hasSize(1);
    assertThat(reloaded.get(0))
      .matches(p -> Objects.equals(p.getId(), activeRuleParam.getId()))
      .matches(p -> p.getKey().equals(activeRuleParam.getKey()))
      .matches(p -> p.getActiveRuleId().equals(activeRule.getId()))
      .matches(p -> p.getRulesParameterId().equals(rule1Param1.getId()))
      .matches(p -> p.getValue().equals("foo"));
  }

  @Test
  public void insertParam_fails_when_active_rule_id_is_null() {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("ActiveRule is not persisted");

    underTest.insertParam(dbSession,
      createFor(profile1, rule1).setId(null),
      ActiveRuleParamDto.createFor(rule1Param1).setValue("activeValue1"));
  }

  @Test
  public void insertParam_fails_when_active_rule_param_id_is_null() {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("ActiveRuleParam is already persisted");

    underTest.insertParam(dbSession,
      createFor(profile1, rule1).setId(100),
      ActiveRuleParamDto.createFor(rule1Param1).setValue("activeValue1").setId(100));
  }

  @Test
  public void insertParam_fails_when_id_is_not_null() {
    thrown.expect(NullPointerException.class);
    thrown.expectMessage("Rule param is not persisted");

    underTest.insertParam(dbSession,
      createFor(profile1, rule1).setId(100),
      ActiveRuleParamDto.createFor(rule1Param1).setValue("activeValue1").setRulesParameterId(null));
  }

  @Test
  public void updateParam() {
    ActiveRuleDto activeRule = createFor(profile1, rule1).setSeverity(Severity.CRITICAL);
    underTest.insert(dbSession, activeRule);
    ActiveRuleParamDto activeRuleParam = ActiveRuleParamDto.createFor(rule1Param1).setValue("foo");
    underTest.insertParam(dbSession, activeRule, activeRuleParam);

    underTest.updateParam(dbSession, activeRuleParam.setValue("bar"));

    List<ActiveRuleParamDto> reloaded = underTest.selectParamsByActiveRuleId(dbSession, activeRule.getId());
    assertThat(reloaded).hasSize(1);
    assertThat(reloaded.get(0))
      .matches(p -> Objects.equals(p.getId(), activeRuleParam.getId()))
      .matches(p -> p.getKey().equals(activeRuleParam.getKey()))
      .matches(p -> p.getActiveRuleId().equals(activeRule.getId()))
      .matches(p -> p.getRulesParameterId().equals(rule1Param1.getId()))
      .matches(p -> p.getValue().equals("bar"));
  }

  @Test
  public void deleteParam_deletes_rows_by_id() {
    ActiveRuleDto activeRule = newRow(profile1, rule1);
    underTest.insert(dbSession, activeRule);
    ActiveRuleParamDto param = ActiveRuleParamDto.createFor(rule1Param1).setValue("foo");
    underTest.insertParam(dbSession, activeRule, param);

    underTest.deleteParam(dbSession, param);

    assertThat(underTest.selectParamsByActiveRuleId(dbSession, activeRule.getId())).hasSize(0);
  }

  @Test
  public void deleteParametersByRuleProfileUuids_deletes_rows_by_rule_profile_uuids() {
    ActiveRuleDto activeRuleInProfile1 = newRow(profile1, rule1);
    underTest.insert(dbSession, activeRuleInProfile1);
    ActiveRuleParamDto param1 = ActiveRuleParamDto.createFor(rule1Param1).setValue("foo");
    underTest.insertParam(dbSession, activeRuleInProfile1, param1);
    ActiveRuleDto activeRuleInProfile2 = newRow(profile2, rule1);
    underTest.insert(dbSession, activeRuleInProfile2);
    ActiveRuleParamDto param2 = ActiveRuleParamDto.createFor(rule1Param1).setValue("bar");
    underTest.insertParam(dbSession, activeRuleInProfile2, param2);

    underTest.deleteParametersByRuleProfileUuids(dbSession, asList(profile1.getRulesProfileUuid(), "does_not_exist"));

    assertThat(underTest.selectParamsByActiveRuleId(dbSession, activeRuleInProfile1.getId())).isEmpty();
    assertThat(underTest.selectParamsByActiveRuleId(dbSession, activeRuleInProfile2.getId()))
      .extracting(ActiveRuleParamDto::getKey, ActiveRuleParamDto::getValue)
      .containsExactly(tuple(rule1Param1.getName(), "bar"));
  }

  @Test
  public void deleteParametersByRuleProfileUuids_does_nothing_if_keys_are_empty() {
    ActiveRuleDto activeRuleInProfile1 = newRow(profile1, rule1);
    underTest.insert(dbSession, activeRuleInProfile1);
    ActiveRuleParamDto param1 = ActiveRuleParamDto.createFor(rule1Param1).setValue("foo");
    underTest.insertParam(dbSession, activeRuleInProfile1, param1);

    underTest.deleteParametersByRuleProfileUuids(dbSession, emptyList());

    assertThat(underTest.selectParamsByActiveRuleId(dbSession, activeRuleInProfile1.getId()))
      .hasSize(1);
  }

  @Test
  public void deleteParamsByRuleParamOfAllOrganizations() {
    ActiveRuleDto activeRule1 = createFor(profile1, rule1).setSeverity(BLOCKER);
    underTest.insert(dbSession, activeRule1);
    ActiveRuleParamDto activeRuleParam1 = ActiveRuleParamDto.createFor(rule1Param1).setValue("foo");
    underTest.insertParam(dbSession, activeRule1, activeRuleParam1);

    ActiveRuleDto activeRule2 = createFor(profile2, rule1).setSeverity(BLOCKER);
    underTest.insert(dbSession, activeRule2);
    ActiveRuleParamDto activeRuleParam2 = ActiveRuleParamDto.createFor(rule1Param1).setValue("bar");
    underTest.insertParam(dbSession, activeRule2, activeRuleParam2);

    List<Integer> activeRuleIds = asList(activeRule1.getId(), activeRule2.getId());
    assertThat(underTest.selectParamsByActiveRuleIds(dbSession, activeRuleIds)).hasSize(2);

    underTest.deleteParamsByRuleParamOfAllOrganizations(dbSession, rule1Param1);

    assertThat(underTest.selectParamsByActiveRuleIds(dbSession, activeRuleIds)).isEmpty();
  }

  @Test
  public void deleteParamsByActiveRuleIds() {
    ActiveRuleDto ar1 = underTest.insert(dbSession, newRow(profile1, rule1));
    ActiveRuleParamDto param = ActiveRuleParamDto.createFor(rule1Param1).setValue("foo");
    underTest.insertParam(dbSession, ar1, param);

    ActiveRuleDto ar2 = underTest.insert(dbSession, newRow(profile1, rule2));
    ActiveRuleParamDto param2 = ActiveRuleParamDto.createFor(rule2Param1).setValue("bar");
    underTest.insertParam(dbSession, ar2, param2);

    underTest.deleteParamsByActiveRuleIds(dbSession, asList(ar1.getId()));

    assertThat(underTest.selectParamsByActiveRuleId(dbSession, ar1.getId())).hasSize(0);
    assertThat(underTest.selectParamsByActiveRuleId(dbSession, ar2.getId())).hasSize(1);
  }

  @Test
  public void test_countActiveRulesByProfileKey_for_a_specified_organization() {
    db.qualityProfiles().activateRule(profile1, rule1);
    db.qualityProfiles().activateRule(profile1, rule2);
    db.qualityProfiles().activateRule(profile2, rule1);

    Map<String, Long> counts = underTest.countActiveRulesByProfileUuid(dbSession, organization);

    assertThat(counts).containsOnly(
      entry(profile1.getKee(), 2L),
      entry(profile2.getKee(), 1L));
  }

  @Test
  public void countActiveRulesByProfileKey_returns_empty_map_if_organization_does_not_exist() {
    Map<String, Long> counts = underTest.countActiveRulesByProfileUuid(dbSession, OrganizationTesting.newOrganizationDto());

    assertThat(counts).isEmpty();
  }

  @Test
  public void countActiveRulesByProfileKey_returns_empty_map_if_profile_does_not_have_active_rules() {
    Map<String, Long> counts = underTest.countActiveRulesByProfileUuid(dbSession, organization);

    assertThat(counts).isEmpty();
  }

  @Test
  public void countActiveRulesByProfileKey_ignores_removed_rules() {
    db.qualityProfiles().activateRule(profile1, rule1);
    db.qualityProfiles().activateRule(profile1, removedRule);

    Map<String, Long> counts = underTest.countActiveRulesByProfileUuid(dbSession, organization);

    assertThat(counts).containsExactly(entry(profile1.getKee(), 1L));
  }

  @Test
  public void test_countActiveRulesForRuleStatusByProfileKey_for_a_specified_organization() {
    RuleDefinitionDto betaRule1 = db.rules().insertRule(RuleTesting.newRuleDto().setStatus(RuleStatus.BETA)).getDefinition();
    RuleDefinitionDto betaRule2 = db.rules().insertRule(RuleTesting.newRuleDto().setStatus(RuleStatus.BETA)).getDefinition();
    db.qualityProfiles().activateRule(profile1, rule1);
    db.qualityProfiles().activateRule(profile2, betaRule1);
    db.qualityProfiles().activateRule(profile2, betaRule2);

    Map<String, Long> counts = underTest.countActiveRulesForRuleStatusByProfileUuid(dbSession, organization, RuleStatus.BETA);

    assertThat(counts).containsOnly(entry(profile2.getKee(), 2L));
  }

  @Test
  public void countActiveRulesForRuleStatusByProfileKey_returns_empty_map_if_organization_does_not_exist() {
    Map<String, Long> counts = underTest.countActiveRulesForRuleStatusByProfileUuid(dbSession, OrganizationTesting.newOrganizationDto(), RuleStatus.READY);

    assertThat(counts).isEmpty();
  }

  @Test
  public void countActiveRulesForRuleStatusByProfileKey_returns_empty_map_if_profile_does_not_have_rules_with_specified_status() {
    Map<String, Long> counts = underTest.countActiveRulesForRuleStatusByProfileUuid(dbSession, organization, RuleStatus.DEPRECATED);

    assertThat(counts).isEmpty();
  }

  @Test
  public void test_countActiveRulesForInheritanceByProfileKey_for_a_specified_organization() {
    db.qualityProfiles().activateRule(profile1, rule1);
    db.qualityProfiles().activateRule(profile2, rule1, ar -> ar.setInheritance(ActiveRuleDto.OVERRIDES));
    db.qualityProfiles().activateRule(profile2, rule2, ar -> ar.setInheritance(ActiveRuleDto.INHERITED));

    Map<String, Long> counts = underTest.countActiveRulesForInheritanceByProfileUuid(dbSession, organization, ActiveRuleDto.OVERRIDES);

    assertThat(counts).containsOnly(entry(profile2.getKee(), 1L));
  }

  @Test
  public void countActiveRulesForInheritanceByProfileKey_returns_empty_map_if_organization_does_not_exist() {
    Map<String, Long> counts = underTest.countActiveRulesForInheritanceByProfileUuid(dbSession, OrganizationTesting.newOrganizationDto(), ActiveRuleDto.OVERRIDES);

    assertThat(counts).isEmpty();
  }

  @Test
  public void countActiveRulesForInheritanceByProfileKey_returns_empty_map_if_profile_does_not_have_rules_with_specified_status() {
    Map<String, Long> counts = underTest.countActiveRulesForInheritanceByProfileUuid(dbSession, organization, ActiveRuleDto.OVERRIDES);

    assertThat(counts).isEmpty();
  }

  @Test
  public void countActiveRulesForInheritanceByProfileKey_ignores_removed_rules() {
    db.qualityProfiles().activateRule(profile1, rule1, ar -> ar.setInheritance(ActiveRuleDto.OVERRIDES));
    db.qualityProfiles().activateRule(profile1, removedRule, ar -> ar.setInheritance(ActiveRuleDto.OVERRIDES));

    Map<String, Long> counts = underTest.countActiveRulesForInheritanceByProfileUuid(dbSession, organization, ActiveRuleDto.OVERRIDES);

    assertThat(counts).containsOnly(entry(profile1.getKee(), 1L));
  }
}
