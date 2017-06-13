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
// @flow
import React from 'react';
import Helmet from 'react-helmet';
import ProjectActivityPageHeader from './ProjectActivityPageHeader';
import ProjectActivityAnalysesList from './ProjectActivityAnalysesList';
import ProjectActivityPageFooter from './ProjectActivityPageFooter';
import * as actions from '../actions';
import { parseQuery, serializeUrlQuery } from '../utils';
import { translate } from '../../../helpers/l10n';
import './projectActivity.css';
import type { Query, ProjectActivityState as State } from '../types';
import type { RawQuery } from '../../../helpers/query';

type Props = {
  addEvent: (string, string, string | void) => Promise<*>,
  changeEvent: (string, string) => Promise<*>,
  deleteAnalysis: string => Promise<*>,
  deleteEvent: string => Promise<*>,
  fetchActivity: (Query, Object | void) => Promise<*>,
  location: { pathname: string, query: RawQuery },
  project: { configuration?: { showHistory: boolean }, key: string },
  router: { push: ({ pathname: string, query?: RawQuery }) => void }
};

export default class ProjectActivityApp extends React.PureComponent {
  mounted: boolean;
  props: Props;
  state: State;

  constructor(ownProps: Props) {
    super(ownProps);
    this.state = { loading: true, query: parseQuery(ownProps.location.query) };
  }

  componentDidMount() {
    this.mounted = true;
    this.handleQueryChange();
    const elem = document.querySelector('html');
    elem && elem.classList.add('dashboard-page');
  }

  componentDidUpdate(prevProps: Props) {
    if (prevProps.location.query !== this.props.location.query) {
      this.handleQueryChange();
    }
  }

  componentWillUnmount() {
    this.mounted = false;
    const elem = document.querySelector('html');
    elem && elem.classList.remove('dashboard-page');
  }

  fetchMoreActivity = () => {
    const { paging, query } = this.state;
    if (!paging) {
      return;
    }

    this.setState({ loading: true });
    this.props.fetchActivity(query, { p: paging.pageIndex + 1 }).then(({ analyses, paging }) => {
      if (this.mounted) {
        this.setState((state: State) => ({
          analyses: state.analyses ? state.analyses.concat(analyses) : analyses,
          loading: false,
          paging
        }));
      }
    });
  };

  addCustomEvent = (analysis: string, name: string, category?: string) =>
    this.props
      .addEvent(analysis, name, category)
      .then(
        ({ analysis, event }) =>
          this.mounted && this.setState(actions.addCustomEvent(analysis, event))
      );

  addVersion = (analysis: string, version: string) =>
    this.addCustomEvent(analysis, version, 'VERSION');

  deleteEvent = (analysis: string, event: string) =>
    this.props
      .deleteEvent(event)
      .then(() => this.mounted && this.setState(actions.deleteEvent(analysis, event)));

  changeEvent = (event: string, name: string) =>
    this.props
      .changeEvent(event, name)
      .then(
        ({ analysis, event }) => this.mounted && this.setState(actions.changeEvent(analysis, event))
      );

  deleteAnalysis = (analysis: string) =>
    this.props
      .deleteAnalysis(analysis)
      .then(() => this.mounted && this.setState(actions.deleteAnalysis(analysis)));

  handleQueryChange() {
    const query = parseQuery(this.props.location.query);
    this.setState({ loading: true, query });
    this.props.fetchActivity(query).then(({ analyses, paging }) => {
      if (this.mounted) {
        this.setState({
          analyses,
          loading: false,
          paging
        });
      }
    });
  }

  updateQuery = (newQuery: { [string]: ?string }) => {
    this.props.router.push({
      pathname: this.props.location.pathname,
      query: {
        ...serializeUrlQuery({
          ...this.state.query,
          ...newQuery
        }),
        id: this.props.project.key
      }
    });
  };

  render() {
    const { query } = this.state;
    const { configuration } = this.props.project;
    const canAdmin = configuration ? configuration.showHistory : false;

    return (
      <div id="project-activity" className="page page-limited">
        <Helmet title={translate('project_activity.page')} />

        <ProjectActivityPageHeader category={query.category} updateQuery={this.updateQuery} />

        <ProjectActivityAnalysesList
          addCustomEvent={this.addCustomEvent}
          addVersion={this.addVersion}
          analyses={this.state.analyses}
          canAdmin={canAdmin}
          changeEvent={this.changeEvent}
          deleteAnalysis={this.deleteAnalysis}
          deleteEvent={this.deleteEvent}
        />

        <ProjectActivityPageFooter
          analyses={this.state.analyses}
          fetchMoreActivity={this.fetchMoreActivity}
          paging={this.state.paging}
        />
      </div>
    );
  }
}
