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
import AddEventForm from './AddEventForm';
import type { Analysis } from '../../types';

type Props = {
  addCustomEvent: (string, string, string | void) => Promise<*>,
  analysis: Analysis
};

export default function AddCustomEventForm({ addCustomEvent, analysis }: Props) {
  return (
    <AddEventForm
      addEvent={addCustomEvent}
      analysis={analysis}
      addEventButtonText="project_activity.add_custom_event"
    />
  );
}
