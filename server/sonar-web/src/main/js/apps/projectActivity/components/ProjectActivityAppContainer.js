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
import { connect } from 'react-redux';
import { withRouter } from 'react-router';
import ProjectActivityApp from './ProjectActivityApp';
import { getComponent } from '../../../store/rootReducer';
import { onFail } from '../../../store/rootActions';
import * as api from '../../../api/projectActivity';
import { serializeQuery } from '../utils';
import type { Query } from '../types';

const addEvent = (analysis: string, name: string, category?: string) => dispatch =>
  api
    .createEvent(analysis, name, category)
    .then(({ analysis, ...event }) => ({ analysis, event }))
    .catch(onFail(dispatch));

const changeEvent = (event: string, name: string) => dispatch =>
  api
    .changeEvent(event, name)
    .then(({ analysis, ...event }) => ({ analysis, event }))
    .catch(onFail(dispatch));

const deleteAnalysis = (analysis: string) => dispatch =>
  api.deleteAnalysis(analysis).catch(onFail(dispatch));

const deleteEvent = (event: string) => dispatch => api.deleteEvent(event).catch(onFail(dispatch));

const fetchActivity = (query: Query, additional?: {}) => dispatch => {
  const parameters = {
    ...serializeQuery(query),
    ...additional
  };
  return api.getProjectActivity(parameters).catch(onFail(dispatch));
};

const mapStateToProps = (state, ownProps) => ({
  project: getComponent(state, ownProps.location.query.id)
});

const mapDispatchToProps = {
  addEvent,
  deleteEvent,
  changeEvent,
  deleteAnalysis,
  fetchActivity
};

export default connect(mapStateToProps, mapDispatchToProps)(withRouter(ProjectActivityApp));
