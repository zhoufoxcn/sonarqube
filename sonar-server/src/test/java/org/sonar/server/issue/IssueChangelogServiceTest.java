/*
 * SonarQube, open source software quality management tool.
 * Copyright (C) 2008-2013 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * SonarQube is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * SonarQube is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.issue;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;
import org.sonar.api.issue.Issue;
import org.sonar.api.issue.IssueQuery;
import org.sonar.api.issue.IssueQueryResult;
import org.sonar.api.issue.internal.DefaultIssue;
import org.sonar.api.issue.internal.FieldDiffs;
import org.sonar.api.user.User;
import org.sonar.api.user.UserFinder;
import org.sonar.core.issue.DefaultIssueQueryResult;
import org.sonar.core.issue.db.IssueChangeDao;
import org.sonar.core.user.DefaultUser;
import org.sonar.server.exceptions.NotFoundException;
import org.sonar.server.user.MockUserSession;

import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;

import static com.google.common.collect.Lists.newArrayList;
import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.Fail.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class IssueChangelogServiceTest {

  @Mock
  IssueChangeDao changeDao;

  @Mock
  UserFinder userFinder;

  @Mock
  DefaultIssueFinder issueFinder;

  @Mock
  IssueChangelogFormatter formatter;

  IssueChangelogService service;

  @Before
  public void setUp() throws Exception {
    service = new IssueChangelogService(changeDao, userFinder, issueFinder, formatter);
  }

  @Test
  public void load_changelog_and_related_users() throws Exception {
    FieldDiffs userChange = new FieldDiffs().setUserLogin("arthur").setDiff("severity", "MAJOR", "BLOCKER");
    FieldDiffs scanChange = new FieldDiffs().setDiff("status", "RESOLVED", "CLOSED");
    when(changeDao.selectChangelogByIssue("ABCDE")).thenReturn(Arrays.asList(userChange, scanChange));
    User arthur = new DefaultUser().setLogin("arthur").setName("Arthur");
    when(userFinder.findByLogins(Arrays.asList("arthur"))).thenReturn(Arrays.asList(arthur));

    IssueQueryResult issueQueryResult = new DefaultIssueQueryResult(newArrayList((Issue) new DefaultIssue().setKey("ABCDE")));
    when(issueFinder.find(any(IssueQuery.class))).thenReturn(issueQueryResult);

    IssueChangelog changelog = service.changelog("ABCDE");

    assertThat(changelog).isNotNull();
    assertThat(changelog.changes()).containsOnly(userChange, scanChange);
    assertThat(changelog.user(scanChange)).isNull();
    assertThat(changelog.user(userChange)).isSameAs(arthur);
  }

  @Test
  public void not_load_changelog_on_unkown_issue() throws Exception {
    try {
      IssueQueryResult issueQueryResult = new DefaultIssueQueryResult(Collections.<Issue>emptyList());
      when(issueFinder.find(any(IssueQuery.class))).thenReturn(issueQueryResult);

      service.changelog("ABCDE");
      fail();
    } catch (Exception e) {
      assertThat(e).isInstanceOf(NotFoundException.class);
      verifyNoMoreInteractions(changeDao);
      verifyNoMoreInteractions(userFinder);
    }
  }

  @Test
  public void format_diffs() throws Exception {
    FieldDiffs diffs = new FieldDiffs().setUserLogin("arthur").setDiff("severity", "MAJOR", "BLOCKER");
    MockUserSession.set();
    service.formatDiffs(diffs);
    verify(formatter).format(any(Locale.class), eq(diffs));
  }
}
