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
package it.issue;

import com.sonar.orchestrator.Orchestrator;
import it.Category2Suite;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import pageobjects.Navigation;
import pageobjects.issues.Issue;
import pageobjects.issues.IssuesPage;
import util.ItUtils;
import util.user.UserRule;

import static util.ItUtils.runProjectAnalysis;

public class IssuesPageTest {
  private static final String PROJECT_KEY = "sample";

  @ClassRule
  public static Orchestrator ORCHESTRATOR = Category2Suite.ORCHESTRATOR;

  @ClassRule
  public static UserRule userRule = UserRule.from(ORCHESTRATOR);

  @BeforeClass
  public static void prepareData() {
    ORCHESTRATOR.resetData();

    ItUtils.restoreProfile(ORCHESTRATOR, IssuesPageTest.class.getResource("/issue/with-many-rules.xml"));

    ORCHESTRATOR.getServer().provisionProject(PROJECT_KEY, PROJECT_KEY);
    ORCHESTRATOR.getServer().associateProjectToQualityProfile(PROJECT_KEY, "xoo", "with-many-rules");
    runProjectAnalysis(ORCHESTRATOR, "shared/xoo-multi-modules-sample");
  }

  @Test
  public void should_display_actions() {
    Navigation nav = new Navigation(ORCHESTRATOR);
    IssuesPage page = nav.logIn().asAdmin().openIssues();
    Issue issue = page.getFirstIssue();
    issue.shouldAllowAssign().shouldAllowChangeType();
  }

  @Test
  public void should_not_display_actions() {
    Navigation nav = new Navigation(ORCHESTRATOR);
    IssuesPage page = nav.openIssues();
    Issue issue = page.getFirstIssue();
    issue.shouldNotAllowAssign().shouldNotAllowChangeType();
  }
}
